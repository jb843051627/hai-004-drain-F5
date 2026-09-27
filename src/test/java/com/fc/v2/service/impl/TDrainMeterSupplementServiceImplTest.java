package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.fc.v2.common.exception.DrainMeterSupplementException;
import com.fc.v2.mapper.auto.TDrainMeterBatchMapper;
import com.fc.v2.mapper.auto.TDrainMeterRowMapper;
import com.fc.v2.model.auto.TDrainMeterBatch;
import com.fc.v2.model.auto.TDrainMeterRow;

/**
 * 机房机组温/湿度断传人工补录红线测试（内存库模拟明细表）：
 * 断传天才许补、未断传不许手补、非温湿度表计不走这条口子、
 * 断传一天只许一笔、照实抄时刻不拿补录时刻顶替、剔除行不算远传到数、补录行不进上期对表。
 *
 * @author fuce
 * @date 2026-09-26
 */
class TDrainMeterSupplementServiceImplTest {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private TDrainMeterRowServiceImpl service;
    private final Map<Long, TDrainMeterRow> store = new LinkedHashMap<>();
    private final Map<String, TDrainMeterBatch> heads = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(5000);

    @BeforeEach
    void setUp() {
        store.clear();
        heads.clear();
        seq.set(5000);
        TDrainMeterRowMapper mapper = mock(TDrainMeterRowMapper.class);
        TDrainMeterBatchMapper batchMapper = mock(TDrainMeterBatchMapper.class);

        when(mapper.insert(any())).thenAnswer(inv -> {
            TDrainMeterRow r = inv.getArgument(0);
            // 模拟库唯一键 uk_item_source_read：同表计同来源同抄表时刻只许一笔
            for (TDrainMeterRow x : store.values()) {
                if (sameStr(x.getItemCode(), r.getItemCode())
                        && eqInt(x.getSource(), r.getSource())
                        && x.getReadTime() != null && r.getReadTime() != null
                        && FMT.format(x.getReadTime()).equals(FMT.format(r.getReadTime()))) {
                    throw new DuplicateKeyException("uk_item_source_read");
                }
            }
            if (r.getId() == null) {
                r.setId(seq.incrementAndGet());
            }
            store.put(r.getId(), r);
            return 1;
        });

        // 与 countRemoteAccepted SQL 同口径（远传未带 read_time 的按 create_time 归天，测试行都带）
        when(mapper.countRemoteAccepted(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String item = inv.getArgument(0);
            String ds = inv.getArgument(1);
            String de = inv.getArgument(2);
            return (int) store.values().stream()
                    .filter(r -> Integer.valueOf(0).equals(r.getDelFlag()))
                    .filter(r -> Integer.valueOf(0).equals(r.getSource()))
                    .filter(r -> Integer.valueOf(1).equals(r.getStatus()))
                    .filter(r -> item.equals(r.getItemCode()))
                    .filter(r -> r.getReadTime() != null)
                    .filter(r -> cmp(FMT.format(r.getReadTime()), ds) >= 0
                            && cmp(FMT.format(r.getReadTime()), de) < 0)
                    .count();
        });
        when(mapper.findManualAccepted(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String item = inv.getArgument(0);
            String ds = inv.getArgument(1);
            String de = inv.getArgument(2);
            List<TDrainMeterRow> hit = store.values().stream()
                    .filter(r -> Integer.valueOf(0).equals(r.getDelFlag()))
                    .filter(r -> Integer.valueOf(1).equals(r.getSource()))
                    .filter(r -> Integer.valueOf(1).equals(r.getStatus()))
                    .filter(r -> item.equals(r.getItemCode()))
                    .filter(r -> r.getReadTime() != null)
                    .filter(r -> cmp(FMT.format(r.getReadTime()), ds) >= 0
                            && cmp(FMT.format(r.getReadTime()), de) < 0)
                    .sorted(Comparator.comparing(TDrainMeterRow::getReadTime))
                    .collect(Collectors.toList());
            return hit.isEmpty() ? null : hit.get(0);
        });

        when(batchMapper.insert(any(TDrainMeterBatch.class))).thenReturn(1);
        when(batchMapper.selectForUpdate(anyString())).thenAnswer(inv -> heads.get(inv.getArgument(0)));

        service = new TDrainMeterRowServiceImpl();
        ReflectionTestUtils.setField(service, "drainMeterRowMapper", mapper);
        ReflectionTestUtils.setField(service, "drainMeterBatchMapper", batchMapper);
    }

    private static int cmp(String a, String b) {
        return a.compareTo(b);
    }

    private static boolean sameStr(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean eqInt(Integer a, Integer b) {
        return a == null ? b == null : a.equals(b);
    }

    private TDrainMeterRow remote(String item, String readTime, int status) throws Exception {
        TDrainMeterRow r = new TDrainMeterRow();
        r.setItemCode(item);
        r.setQty(new BigDecimal("36.5"));
        r.setSource(0);
        r.setStatus(status);
        r.setReadTime(FMT.parse(readTime));
        r.setDelFlag(0);
        r.setId(seq.incrementAndGet());
        store.put(r.getId(), r);
        return r;
    }

    private Date past(String hhmmss) throws Exception {
        // 用固定昨天日期，保证抄表时刻早于当前，不触发"不许预补"
        return FMT.parse("2026-09-25 " + hhmmss);
    }

    // 1. 断传当天（无对平远传行）：温度表照抄表时刻补录成功，落 source=1
    @Test
    void outageDay_tempSupplementAccepted() throws Exception {
        Long id = service.supplementManual("PS00-TEMP-01", new BigDecimal("42.0"), past("08:00:00"));

        TDrainMeterRow saved = store.get(id);
        assertEquals(1, saved.getSource().intValue(), "来源=断传人工补录");
        assertEquals(1, saved.getStatus().intValue(), "补录即对平");
        assertEquals("PS00-TEMP-01", saved.getItemCode());
        assertEquals(FMT.parse("2026-09-25 08:00:00"), saved.getReadTime(), "照实抄时刻，不拿补录时刻顶替");
        assertNull(saved.getBatchNo(), "补录行走人工口子，不挂远传批次号");
        assertTrue(saved.getRemark().contains("温度"));
    }

    // 2. 未断传（当天已有对平远传行）：手工补一律拒绝
    @Test
    void remoteAliveDay_manualRejected() throws Exception {
        remote("PS00-TEMP-01", "2026-09-25 10:00:00", 1);

        DrainMeterSupplementException ex = assertThrows(DrainMeterSupplementException.class,
                () -> service.supplementManual("PS00-TEMP-01", new BigDecimal("42.0"), past("08:00:00")));
        assertTrue(ex.getMessage().contains("未断"));
        assertEquals(1, store.size(), "拒绝即不入库");
    }

    // 3. 远传行当天被剔除（status=2）等于没数：仍按断传认，允许补
    @Test
    void remoteRowRejectedThatDay_countsAsOutage() throws Exception {
        remote("PS00-HUMI-01", "2026-09-25 10:00:00", 2);

        Long id = service.supplementManual("PS00-HUMI-01", new BigDecimal("88.0"), past("12:00:00"));
        assertEquals(1, store.get(id).getSource().intValue());
        assertTrue(store.get(id).getRemark().contains("湿度"));
    }

    // 4. 断传一天只许补一笔：同日再补（哪怕换个时刻）挡下
    @Test
    void oneManualPerDay_secondRejected() throws Exception {
        service.supplementManual("PS00-TEMP-01", new BigDecimal("42.0"), past("08:00:00"));

        DrainMeterSupplementException ex = assertThrows(DrainMeterSupplementException.class,
                () -> service.supplementManual("PS00-TEMP-01", new BigDecimal("43.0"), past("20:00:00")));
        assertTrue(ex.getMessage().contains("一天只许补一次"));
        assertEquals(1, store.values().stream().filter(r -> Integer.valueOf(1).equals(r.getSource())).count());
    }

    // 5. 非温湿度表计（电度 EM）不走断传补录口子
    @Test
    void nonGaugeMeter_rejected() {
        DrainMeterSupplementException ex = assertThrows(DrainMeterSupplementException.class,
                () -> service.supplementManual("PS00-EM01", new BigDecimal("100"), past("08:00:00")));
        assertTrue(ex.getMessage().contains("温度/湿度表计"));
        assertTrue(store.isEmpty());
    }

    // 6. 编码按段整段认：TEMPX 这种子串不算温度表
    @Test
    void gaugeMatchedBySegment_notSubstring() throws Exception {
        assertThrows(DrainMeterSupplementException.class,
                () -> service.supplementManual("PS00-TEMPX-01", new BigDecimal("1"), past("08:00:00")));
        // 下划线分段同样认
        Long id = service.supplementManual("PS00_humi_02", new BigDecimal("80.0"), past("08:00:00"));
        assertEquals(1, store.get(id).getSource().intValue());
    }

    // 7. 抄表时刻缺失（想拿补录时刻顶替）：拒绝
    @Test
    void readTimeMissing_rejected() {
        assertThrows(DrainMeterSupplementException.class,
                () -> service.supplementManual("PS00-TEMP-01", new BigDecimal("42.0"), null));
    }

    // 8. 不许预补未来时刻
    @Test
    void futureReadTime_rejected() {
        Date future = new Date(System.currentTimeMillis() + 86400000L);
        assertThrows(DrainMeterSupplementException.class,
                () -> service.supplementManual("PS00-TEMP-01", new BigDecimal("42.0"), future));
    }

    // 9. 读数缺失/为负/超量程拒绝
    @Test
    void badQty_rejected() {
        assertThrows(DrainMeterSupplementException.class,
                () -> service.supplementManual("PS00-TEMP-01", null, past("08:00:00")));
        assertThrows(DrainMeterSupplementException.class,
                () -> service.supplementManual("PS00-TEMP-01", new BigDecimal("-1"), past("08:00:00")));
        assertThrows(DrainMeterSupplementException.class,
                () -> service.supplementManual("PS00-TEMP-01",
                        new BigDecimal("12345678901.00"), past("08:00:00")));
        assertTrue(store.isEmpty());
    }

    // 10. isRemoteOutage 判定口径：断传 true，没断 false，非温湿度表计/缺时刻一律 false
    @Test
    void outageQuery_口径() throws Exception {
        remote("PS00-TEMP-01", "2026-09-25 10:00:00", 1);

        assertFalse(service.isRemoteOutage("PS00-TEMP-01", past("20:00:00")), "当天有远传对平行，没断");
        assertTrue(service.isRemoteOutage("PS00-TEMP-02", past("20:00:00")), "另一支温度表当天无数，断传");
        assertFalse(service.isRemoteOutage("PS00-EM01", past("20:00:00")), "非温湿度表计不可补");
        assertFalse(service.isRemoteOutage("PS00-TEMP-01", null), "缺时刻不可补");
        assertFalse(service.isRemoteOutage("  ", past("20:00:00")), "空编码不可补");
    }

    // 11. 不同天：昨天补过，今天断传仍可补（一天一笔，不跨天连坐）
    @Test
    void differentDays_eachCanSupplement() throws Exception {
        service.supplementManual("PS00-TEMP-01", new BigDecimal("42.0"),
                FMT.parse("2026-09-24 08:00:00"));
        Long id = service.supplementManual("PS00-TEMP-01", new BigDecimal("41.0"),
                FMT.parse("2026-09-25 08:00:00"));
        assertEquals(2, store.size());
        assertEquals(1, store.get(id).getSource().intValue());
    }

    // 12. 同一抄表时刻补两笔（双击/并发）：第二笔被挡，库里只留一笔
    @Test
    void duplicateSameReadTime_secondGuarded() throws Exception {
        Date t = past("08:00:00");
        service.supplementManual("PS00-TEMP-01", new BigDecimal("42.0"), t);

        DrainMeterSupplementException ex = assertThrows(DrainMeterSupplementException.class,
                () -> service.supplementManual("PS00-TEMP-01", new BigDecimal("42.0"), t));
        assertTrue(ex.getMessage().contains("补录"), "一天一笔/唯一键兜底，消息点明补录");
        assertEquals(1, store.values().stream().filter(r -> Integer.valueOf(1).equals(r.getSource())).count());
    }
}
