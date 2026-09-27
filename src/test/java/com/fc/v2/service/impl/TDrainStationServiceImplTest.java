package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.exception.DrainDispatchException;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainStation;

/**
 * 在册泵站档案红线测试（内存库模拟表）：
 * 台账只摆未删、编号唯一、状态只许 0/1、改状态单口进出、阈值不倒挂。
 *
 * @author fuce
 * @date 2026-09-26
 */
class TDrainStationServiceImplTest {

    private TDrainStationServiceImpl service;
    private final Map<Long, TDrainStation> stations = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        stations.clear();
        seq.set(100);

        TDrainStationMapper mapper = mock(TDrainStationMapper.class);

        when(mapper.selectById(anyLong())).thenAnswer(inv -> stations.get(inv.getArgument(0)));

        when(mapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            Integer status = intEq(p, "status");
            String siteNo = strEq(p, "site_no");
            return stations.values().stream()
                    .filter(s -> !Integer.valueOf(1).equals(s.getDelFlag()))
                    .filter(s -> status == null || status.equals(s.getStatus()))
                    .filter(s -> siteNo == null || siteNo.equals(s.getSiteNo()))
                    .collect(Collectors.toList());
        });

        when(mapper.selectCount(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String siteNo = strEq(p, "site_no");
            Long neId = neLong(p, "id");
            return (int) stations.values().stream()
                    .filter(s -> !Integer.valueOf(1).equals(s.getDelFlag()))
                    .filter(s -> siteNo == null || siteNo.equals(s.getSiteNo()))
                    .filter(s -> neId == null || !neId.equals(s.getId()))
                    .count();
        });

        when(mapper.insert(any())).thenAnswer(inv -> {
            TDrainStation s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(seq.incrementAndGet());
            }
            stations.put(s.getId(), s);
            return 1;
        });

        when(mapper.updateById(any())).thenAnswer(inv -> {
            TDrainStation patch = inv.getArgument(0);
            TDrainStation cur = stations.get(patch.getId());
            if (cur == null) {
                return 0;
            }
            if (patch.getSiteNo() != null) {
                cur.setSiteNo(patch.getSiteNo());
            }
            if (patch.getSiteName() != null) {
                cur.setSiteName(patch.getSiteName());
            }
            if (patch.getStatus() != null) {
                cur.setStatus(patch.getStatus());
            }
            if (patch.getTh1Max() != null) {
                cur.setTh1Max(patch.getTh1Max());
            }
            if (patch.getTh2Max() != null) {
                cur.setTh2Max(patch.getTh2Max());
            }
            if (patch.getTh3Max() != null) {
                cur.setTh3Max(patch.getTh3Max());
            }
            return 1;
        });

        service = new TDrainStationServiceImpl();
        ReflectionTestUtils.setField(service, "drainStationMapper", mapper);
    }

    private TDrainStation stored(String siteNo, int status) {
        TDrainStation s = new TDrainStation();
        s.setId(seq.incrementAndGet());
        s.setSiteNo(siteNo);
        s.setSiteName(siteNo + "号泵站");
        s.setStatus(status);
        s.setTh1Max(new BigDecimal("1"));
        s.setTh2Max(new BigDecimal("5"));
        s.setTh3Max(new BigDecimal("10"));
        s.setDelFlag(0);
        stations.put(s.getId(), s);
        return s;
    }

    private TDrainStation form(String siteNo, String name) {
        TDrainStation s = new TDrainStation();
        s.setSiteNo(siteNo);
        s.setSiteName(name);
        return s;
    }

    // 1. 建档缺省落在用、台账看得见
    @Test
    void insert_defaultsInUse_andShowsInList() {
        assertEquals(1, service.insertTDrainStation(form("PS09", "河湾九号泵站")));

        List<TDrainStation> list = service.selectTDrainStationList(new QueryWrapper<>());
        assertEquals(1, list.size());
        assertEquals(0, list.get(0).getStatus().intValue(), "缺省落在用");
    }

    // 2. 编号重号不许再建
    @Test
    void insert_duplicateSiteNo_rejected() {
        stored("PS00", 0);
        assertThrows(DrainDispatchException.class,
                () -> service.insertTDrainStation(form("PS00", "重号泵站")));
        assertEquals(1, stations.size());
    }

    // 3. 台账只摆未删；已删档案详情也查不到
    @Test
    void list_skipsDeleted_detailHidesDeleted() {
        TDrainStation alive = stored("PS00", 0);
        TDrainStation dead = stored("PS01", 0);
        dead.setDelFlag(1);

        List<TDrainStation> list = service.selectTDrainStationList(new QueryWrapper<>());
        assertEquals(1, list.size());
        assertEquals(alive.getId(), list.get(0).getId());
        assertNull(service.selectTDrainStationById(dead.getId()));
    }

    // 4. 改状态单口进出：在用→停用→改回，台账照实摆
    @Test
    void changeStatus_roundTrip_visibleInList() {
        TDrainStation s = stored("PS00", 0);

        assertEquals(1, service.changeStatus(s.getId(), 1));
        assertEquals(1, stations.get(s.getId()).getStatus().intValue());

        QueryWrapper<TDrainStation> w = new QueryWrapper<>();
        w.eq("status", 1);
        assertEquals(1, service.selectTDrainStationList(w).size(), "停用档案在停用筛选里看得见");

        assertEquals(1, service.changeStatus(s.getId(), 0));
        assertEquals(0, stations.get(s.getId()).getStatus().intValue());
    }

    // 5. 非法状态值挡下
    @Test
    void changeStatus_illegalValue_rejected() {
        TDrainStation s = stored("PS00", 0);
        assertThrows(DrainDispatchException.class, () -> service.changeStatus(s.getId(), 7));
        assertEquals(0, stations.get(s.getId()).getStatus().intValue());
    }

    // 6. 不存在的档案改状态返回 0，不炸
    @Test
    void changeStatus_missing_returnsZero() {
        assertEquals(0, service.changeStatus(999L, 1));
    }

    // 7. 阈值倒挂不许建档
    @Test
    void insert_invertedThresholds_rejected() {
        TDrainStation s = form("PS10", "倒挂泵站");
        s.setTh1Max(new BigDecimal("10"));
        s.setTh2Max(new BigDecimal("5"));
        assertThrows(DrainDispatchException.class, () -> service.insertTDrainStation(s));
        assertTrue(stations.isEmpty());
    }

    // 8. 阈值只维护部分档位可以建档（下游分档顺次往下着）
    @Test
    void insert_partialThresholds_ok() {
        TDrainStation s = form("PS11", "半线泵站");
        s.setTh2Max(new BigDecimal("5"));
        assertEquals(1, service.insertTDrainStation(s));
    }

    // 9. 编辑换编号撞重号被挡
    @Test
    void update_renameToExistingSiteNo_rejected() {
        stored("PS00", 0);
        TDrainStation b = stored("PS01", 0);
        TDrainStation patch = new TDrainStation();
        patch.setId(b.getId());
        patch.setSiteNo("PS00");
        assertThrows(DrainDispatchException.class, () -> service.updateTDrainStation(patch));
        assertEquals("PS01", stations.get(b.getId()).getSiteNo());
    }

    // ---- 参数抠取小工具（QueryWrapper 参数按 "?" 全局先后次序绑定） ----

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
