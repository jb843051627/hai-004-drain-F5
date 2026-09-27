package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TDrainRunRecordMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.model.auto.TDrainStation;

/**
 * 机组运行记录单红线测试（内存库模拟表）：
 * 录一条即待复核且清单看得见、清单不硬塞状态、查询条件生效、停用/缺档站不收、
 * 阈值缺失不崩、单号不重。
 *
 * @author fuce
 * @date 2026-09-26
 */
class TDrainRunRecordServiceImplTest {

    private TDrainRunRecordServiceImpl service;
    private final Map<Long, TDrainRunRecord> records = new LinkedHashMap<>();
    private final Map<Long, TDrainStation> stations = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(5000);

    @BeforeEach
    void setUp() {
        records.clear();
        stations.clear();
        seq.set(5000);

        TDrainRunRecordMapper recordMapper = mock(TDrainRunRecordMapper.class);
        TDrainStationMapper stationMapper = mock(TDrainStationMapper.class);

        when(stationMapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            Object id = first(p, "id", "=");
            return id == null ? null : stations.get(((Number) id).longValue());
        });

        when(recordMapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            Object id = first(p, "id", "=");
            TDrainRunRecord r = id == null ? null : records.get(((Number) id).longValue());
            return r != null && !Integer.valueOf(1).equals(r.getDelFlag()) ? r : null;
        });

        when(recordMapper.selectCount(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String billNo = strEq(p, "bill_no");
            Long neId = neLong(p, "id");
            return (int) records.values().stream()
                    .filter(r -> !Integer.valueOf(1).equals(r.getDelFlag()))
                    .filter(r -> billNo == null || billNo.equals(r.getBillNo()))
                    .filter(r -> neId == null || !neId.equals(r.getId()))
                    .count();
        });

        when(recordMapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            Integer status = intEq(p, "status");
            String billLike = strEq(p, "bill_no");
            String siteNo = strEq(p, "site_no");
            return records.values().stream()
                    .filter(r -> !Integer.valueOf(1).equals(r.getDelFlag()))
                    .filter(r -> status == null || status.equals(r.getStatus()))
                    .filter(r -> billLike == null || (r.getBillNo() != null
                            && r.getBillNo().contains(billLike.replace("%", ""))))
                    .filter(r -> siteNo == null || siteNo.equals(r.getSiteNo()))
                    .collect(Collectors.toList());
        });

        when(recordMapper.insert(any())).thenAnswer(inv -> {
            TDrainRunRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(seq.incrementAndGet());
            }
            records.put(r.getId(), r);
            return 1;
        });

        when(recordMapper.update(any(), any(UpdateWrapper.class))).thenAnswer(inv -> {
            TDrainRunRecord r = inv.getArgument(0);
            records.put(r.getId(), r);
            return 1;
        });

        service = new TDrainRunRecordServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", recordMapper);
        ReflectionTestUtils.setField(service, "drainStationMapper", stationMapper);
    }

    private TDrainStation station(long id, String siteNo, Integer status) {
        return station(id, siteNo, status, new BigDecimal("1"), new BigDecimal("5"), new BigDecimal("10"));
    }

    private TDrainStation station(long id, String siteNo, Integer status,
                                  BigDecimal th1, BigDecimal th2, BigDecimal th3) {
        TDrainStation s = new TDrainStation();
        s.setId(id);
        s.setSiteNo(siteNo);
        s.setSiteName(siteNo + "号泵站");
        s.setStatus(status);
        s.setTh1Max(th1);
        s.setTh2Max(th2);
        s.setTh3Max(th3);
        s.setDelFlag(0);
        stations.put(id, s);
        return s;
    }

    private TDrainRunRecord form(String billNo, int siteId, String qty) {
        TDrainRunRecord r = new TDrainRunRecord();
        r.setBillNo(billNo);
        r.setSiteId(siteId);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    /** 预存一条各字段齐备的记录（模拟别的来源已落库）。 */
    private TDrainRunRecord stored(String billNo, String siteNo, int status) {
        TDrainRunRecord r = new TDrainRunRecord();
        r.setId(seq.incrementAndGet());
        r.setBillNo(billNo);
        r.setSiteNo(siteNo);
        r.setQty(new BigDecimal("2"));
        r.setGradeLevel(1);
        r.setStatus(status);
        r.setDelFlag(0);
        records.put(r.getId(), r);
        return r;
    }

    // 1. 录一条：显式落待复核(0)/未删记号，清单当场看得见——修的就是"明明录过查无此单"
    @Test
    void insert_writesWaitStatus_andShowsInList() {
        station(0L, "PS00", 0);
        TDrainRunRecord r = form("YX001", 0, "0.5");

        assertEquals(1, service.insertTDrainRunRecord(r));
        assertEquals(0, r.getStatus().intValue(), "新录即待复核，不靠库默认值");
        assertEquals(0, r.getDelFlag().intValue());
        assertEquals("PS00", r.getSiteNo(), "站点编号照档案回填");

        List<TDrainRunRecord> list = service.selectTDrainRunRecordList(new QueryWrapper<>());
        assertEquals(1, list.size(), "返回清单就能看见刚录的这一条");
        assertEquals("YX001", list.get(0).getBillNo());
    }

    // 2. 台账不硬塞 status=0：待复核/已复核/已封存都摆
    @Test
    void list_showsAllStatuses_notOnlyWaiting() {
        stored("YX001", "PS00", 0);
        stored("YX002", "PS00", 1);
        stored("YX003", "PS00", 2);

        List<TDrainRunRecord> all = service.selectTDrainRunRecordList(new QueryWrapper<>());
        assertEquals(3, all.size(), "三态都摆，复核过的单不再失踪");
    }

    // 3. 查询条件透传：按状态筛只回该态
    @Test
    void list_honorsStatusFilter() {
        stored("YX001", "PS00", 0);
        stored("YX002", "PS00", 1);

        QueryWrapper<TDrainRunRecord> w = new QueryWrapper<>();
        w.eq("status", 1);
        List<TDrainRunRecord> reviewed = service.selectTDrainRunRecordList(w);
        assertEquals(1, reviewed.size());
        assertEquals("YX002", reviewed.get(0).getBillNo());
    }

    // 4. 档案停用的站不收新单（先在档案里改停用，再录即被挡）
    @Test
    void insert_disabledStation_rejected() {
        station(1L, "PS01", 1);
        TDrainRunRecord r = form("YX009", 1, "0.5");

        assertEquals(0, service.insertTDrainRunRecord(r));
        assertTrue(records.isEmpty(), "停用站新单不落库");
        assertNull(r.getStatus());
    }

    // 5. 档案不存在不收
    @Test
    void insert_missingStation_rejected() {
        TDrainRunRecord r = form("YX010", 404, "0.5");
        assertEquals(0, service.insertTDrainRunRecord(r));
        assertTrue(records.isEmpty());
    }

    // 6. 阈值整档缺失：不许对 null compareTo 把录单整崩，越过全部已划线档归 4 档
    @Test
    void insert_nullThresholds_doesNotThrow() {
        station(2L, "PS02", 0, null, null, null);
        TDrainRunRecord r = form("YX011", 2, "3");

        assertEquals(1, service.insertTDrainRunRecord(r));
        assertEquals(4, r.getGradeLevel().intValue());
    }

    // 7. 只划了二、三档：一档未划线顺次往下着，3 落在二档
    @Test
    void insert_partialThresholds_gradesByDrawnTiers() {
        station(3L, "PS03", 0, null, new BigDecimal("5"), new BigDecimal("10"));
        TDrainRunRecord r = form("YX012", 3, "3");

        assertEquals(1, service.insertTDrainRunRecord(r));
        assertEquals(2, r.getGradeLevel().intValue());
    }

    // 8. 同单号不重录
    @Test
    void insert_duplicateBillNo_rejected() {
        station(0L, "PS00", 0);
        assertEquals(1, service.insertTDrainRunRecord(form("YX-DUP", 0, "0.5")));
        assertEquals(0, service.insertTDrainRunRecord(form("YX-DUP", 0, "0.6")));
        assertEquals(1, records.size());
    }

    // 9. 现场验收路径：档案从在用改停用后，同站再录被挡；改回在用又能录
    @Test
    void stationDisabledViaArchive_thenInsertBlocked_untilReenabled() {
        TDrainStation s = station(0L, "PS00", 0);
        assertEquals(1, service.insertTDrainRunRecord(form("YX020", 0, "0.5")));

        s.setStatus(1); // 在在册泵站档案里改成停用
        assertEquals(0, service.insertTDrainRunRecord(form("YX021", 0, "0.5")),
                "停用后新单进不来，台账状态与档案一致");

        s.setStatus(0); // 改回在用
        assertEquals(1, service.insertTDrainRunRecord(form("YX022", 0, "0.5")),
                "状态改回来下一条自然能录，清单也看得到");
        assertNotNull(service.selectTDrainRunRecordList(new QueryWrapper<>())
                .stream().filter(x -> "YX022".equals(x.getBillNo())).findFirst().orElse(null));
    }

    // ---- 下面三个小工具把 QueryWrapper 里按位次绑定的参数抠出来（与生产 SQL 形状对应） ----
    // QueryWrapper 的参数按 SQL 段里 "?" 的全局先后次序绑定，这里逐段扫描还原名次。

    /** 列名 -> 该列每个占位符绑定的值（含操作符）；占位符形如 #{ew.paramNameValuePairs.MPGENVALn}。 */
    private static Map<String, List<Occ>> parse(String seg, Map<String, Object> params) {
        Map<String, List<Occ>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "(\\w+)\\s*(=|<>|LIKE)\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}").matcher(seg);
        while (m.find()) {
            out.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                    .add(new Occ(m.group(2), params.get(m.group(3))));
        }
        return out;
    }

    private static Object first(Map<String, List<Occ>> parsed, String column, String op) {
        List<Occ> occs = parsed.get(column);
        if (occs == null) {
            return null;
        }
        for (Occ o : occs) {
            if (op == null || o.op.equals(op)) {
                return o.value;
            }
        }
        return null;
    }

    private static String strEq(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "=");
        if (v == null) {
            v = first(p, column, "LIKE");
        }
        return v == null ? null : v.toString();
    }

    private static Integer intEq(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "=");
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private static Long neLong(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "<>");
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static final class Occ {
        final String op;
        final Object value;

        Occ(String op, Object value) {
            this.op = op;
            this.value = value;
        }
    }
}
