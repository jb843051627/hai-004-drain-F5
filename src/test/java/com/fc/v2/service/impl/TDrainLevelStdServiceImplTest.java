package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fc.v2.mapper.auto.TDrainLevelStdMapper;
import com.fc.v2.model.auto.TDrainLevelStd;
import com.fc.v2.model.custom.JudgeResult;

/**
 * 泵站起排判线红线测试（内存库模拟 t_drain_level_std 与 listEffective 的 SQL 口径）：
 * 版本窗口半开交接、空档直回"当下无可用判线"、作废老线不顶、叠版照取优序号两路同一版、
 * 两档存量旧线照旧用、坏水位不收。
 *
 * @author fuce
 * @date 2026-09-28
 */
class TDrainLevelStdServiceImplTest {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private TDrainLevelStdServiceImpl service;
    private final Map<Long, TDrainLevelStd> rows = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        rows.clear();
        TDrainLevelStdMapper mapper = mock(TDrainLevelStdMapper.class);
        when(mapper.selectById(anyLong())).thenAnswer(inv -> rows.get(inv.getArgument(0)));
        // 与 mapper @Select 同口径：未删、现行、窗口齐全、半开 [eff_start, eff_end) 覆盖 at，
        // 按 priority DESC, rule_code DESC, id ASC 排死
        when(mapper.listEffective(anyString())).thenAnswer(inv -> {
            String at = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Integer.valueOf(0).equals(r.getDelFlag()))
                    .filter(r -> Integer.valueOf(0).equals(r.getStatus()))
                    .filter(r -> r.getEffStart() != null && r.getEffEnd() != null)
                    .filter(r -> FMT.format(r.getEffStart()).compareTo(at) <= 0
                            && FMT.format(r.getEffEnd()).compareTo(at) > 0)
                    .sorted(Comparator.comparing(TDrainLevelStd::getPriority,
                                    Comparator.nullsFirst(Comparator.reverseOrder()))
                            .thenComparing(r -> r.getRuleCode() == null ? "" : r.getRuleCode(),
                                    Comparator.reverseOrder())
                            .thenComparing(TDrainLevelStd::getId))
                    .collect(Collectors.toList());
        });
        service = new TDrainLevelStdServiceImpl();
        ReflectionTestUtils.setField(service, "drainLevelStdMapper", mapper);
        seedBaseline();
    }

    /** 与 doc/schema/drain_level_std_seed.sql 同口径的一套底账。 */
    private void seedBaseline() {
        put(200L, "RAIN", "雨水湿季", "2.00", "4.00", "6.00",
                "2026-05-01 00:00:00", "2026-09-01 00:00:00", 50, 0);
        put(201L, "RAIN", "雨水干季", "3.00", "6.00", "9.00",
                "2026-09-01 00:00:00", "2027-05-01 00:00:00", 50, 0);
        put(202L, "COMBINED", "合流旧干季", "3.00", "6.00", "9.00",
                "2026-01-01 00:00:00", "2026-04-01 00:00:00", 50, 0);
        put(203L, "COMBINED", "合流秋冬", "3.00", "6.00", "9.00",
                "2026-10-01 00:00:00", "2027-04-01 00:00:00", 50, 0);
        put(204L, "OLD_FALLBACK", "作废老兜底", "1.00", "2.00", "3.00",
                "2026-01-01 00:00:00", "2027-01-01 00:00:00", 10, 1);
        put(205L, "RAIN_EMG", "汛末应急加严", "1.50", "3.00", "5.00",
                "2026-09-20 00:00:00", "2026-10-10 00:00:00", 90, 0);
        put(210L, "LEGACY_SEWAGE", "两档存量线", "2.50", "5.00", null,
                "2026-01-01 00:00:00", "2027-01-01 00:00:00", 50, 0);
    }

    private void put(long id, String code, String name, String th1, String th2, String th3,
                     String start, String end, int priority, int status) {
        try {
            TDrainLevelStd r = new TDrainLevelStd();
            r.setId(id);
            r.setRuleCode(code);
            r.setRuleName(name);
            r.setTh1Max(th1 == null ? null : new BigDecimal(th1));
            r.setTh2Max(th2 == null ? null : new BigDecimal(th2));
            r.setTh3Max(th3 == null ? null : new BigDecimal(th3));
            r.setEffStart(FMT.parse(start));
            r.setEffEnd(FMT.parse(end));
            r.setPriority(priority);
            r.setStatus(status);
            r.setDelFlag(0);
            rows.put(id, r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 1. 湿季/干季半开交接：切换前一秒取湿季版，切换那一瞬间整取干季版，不重叠
    @Test
    void handover_halfOpenBoundary_picksExactlyOneVersion() throws Exception {
        Date before = FMT.parse("2026-08-31 23:59:59");
        JudgeResult r1 = service.judge("RAIN", new BigDecimal("3.50"), before);
        assertTrue(r1.isAvailable());
        assertEquals(200L, r1.getRuleId());
        assertEquals(2, r1.getLevel().intValue(), "湿季版 3.5 落在二档");

        Date point = FMT.parse("2026-09-01 00:00:00");
        JudgeResult r2 = service.judge("RAIN", new BigDecimal("3.50"), point);
        assertEquals(201L, r2.getRuleId(), "切换整点 end 不含、start 含，整取干季版");
        assertEquals(2, r2.getLevel().intValue(), "干季版 3.5 过一档3.00，落二档");

        // 不指定代号那路在切换点也必须是干季版（两路口径一致）
        assertEquals(201L, service.judge(new BigDecimal("3.50"), point).getRuleId());
        // 切换点全局只有一版 RAIN 生效（湿季版已止），count/list 与判定同源
        long rainCount = service.listAvailable(point).stream()
                .filter(r -> "RAIN".equals(r.getRuleCode())).count();
        assertEquals(1, rainCount, "交接瞬间同代号当下有效的只能有一版");
    }

    // 2. 历史空档：哪版都不沾，直回"当下无可用判线"，老签名回 0，不许翻旧版
    @Test
    void gap_noVersionCovers_returnsNoRule() throws Exception {
        Date gap = FMT.parse("2026-09-15 12:00:00");
        JudgeResult r = service.judge("COMBINED", new BigDecimal("4"), gap);
        assertFalse(r.isAvailable());
        assertNull(r.getLevel());
        assertNull(r.getRuleId());
        assertEquals("当下无可用判线", r.getReason());
        assertEquals(0, service.evaluate("COMBINED", new BigDecimal("4"), gap));
        assertFalse(service.usable(202L, gap), "旧版 4 月就止，9 月不可用");
        assertFalse(service.usable(203L, gap), "新版 10 月才生，9 月不可用");
    }

    // 3. 作废老线窗口再宽也不顶
    @Test
    void voidedRule_neverApplies() throws Exception {
        Date at = FMT.parse("2026-09-28 10:00:00");
        JudgeResult r = service.judge("OLD_FALLBACK", new BigDecimal("1.5"), at);
        assertFalse(r.isAvailable());
        assertEquals("当下无可用判线", r.getReason());
        assertEquals(0, service.evaluate("OLD_FALLBACK", new BigDecimal("1.5"), at));
        assertFalse(service.usable(204L, at));
        assertTrue(service.listAvailable(at).stream().noneMatch(rr -> rr.getId() == 204L));
    }

    // 4. 同刻叠版照取优序号：指定代号与不指定代号两路取的必须同一版
    @Test
    void overlapping_sameCode_priorityWinsBothWays() throws Exception {
        // 同代号 RAIN 再加一版窗口与干季版叠着、priority 更高
        put(220L, "RAIN", "雨水干季加严", "2.00", "4.00", "7.00",
                "2026-09-01 00:00:00", "2026-12-01 00:00:00", 80, 0);
        Date at = FMT.parse("2026-09-25 00:00:00");

        JudgeResult byCode = service.judge("RAIN", new BigDecimal("3.5"), at);
        JudgeResult top = service.judge(new BigDecimal("3.5"), at);
        assertEquals(220L, byCode.getRuleId(), "同代号叠版取 priority 高的，不按 effEnd 挑");
        // 指定代号那路的头版，就是同源列表里该代号按 priority 排的头版——不是另起一套挑法
        TDrainLevelStd firstRain = service.listAvailable(at).stream()
                .filter(r -> "RAIN".equals(r.getRuleCode())).findFirst().orElse(null);
        assertEquals(firstRain.getId(), byCode.getRuleId());
        assertEquals(205L, top.getRuleId(), "全局头版是 priority=90 的应急线");
        assertEquals(3, top.getLevel().intValue(), "应急线 3.5 过二档3.00，落三档(3,5]");
        // 老签名同口径
        assertEquals(byCode.getLevel().intValue(),
                service.evaluate("RAIN", new BigDecimal("3.5"), at));
        assertEquals(top.getLevel().intValue(),
                service.evaluateTop(new BigDecimal("3.5"), at));
    }

    // 5. 两档存量旧线：th3 为 NULL 照旧判，超二档归 4
    @Test
    void legacyTwoTierRule_stillWorks_aboveMaxGoesFour() throws Exception {
        Date at = FMT.parse("2026-09-28 00:00:00");
        assertEquals(1, service.judge("LEGACY_SEWAGE", new BigDecimal("2.50"), at).getLevel().intValue(),
                "等于一档上限取一档(临界取本档)");
        assertEquals(2, service.judge("LEGACY_SEWAGE", new BigDecimal("5.00"), at).getLevel().intValue());
        JudgeResult over = service.judge("LEGACY_SEWAGE", new BigDecimal("5.01"), at);
        assertEquals(4, over.getLevel().intValue(), "没有三档可着，超二档即归 4 档");
        assertEquals(4, service.evaluate("LEGACY_SEWAGE", new BigDecimal("99"), at));
    }

    // 6. 三档线正常分档：临界取本档、超三档归 4
    @Test
    void threeTier_boundariesInclusive_thenFour() throws Exception {
        Date at = FMT.parse("2026-11-01 00:00:00"); // 应急线已止，RAIN 取干季版
        assertEquals(1, service.judge("RAIN", new BigDecimal("3.00"), at).getLevel().intValue());
        assertEquals(2, service.judge("RAIN", new BigDecimal("6.00"), at).getLevel().intValue());
        assertEquals(3, service.judge("RAIN", new BigDecimal("9.00"), at).getLevel().intValue());
        assertEquals(4, service.judge("RAIN", new BigDecimal("9.01"), at).getLevel().intValue());
    }

    // 7. 坏水位：负数、数量级明显不对不收，不做判定
    @Test
    void badWaterLevel_rejected_noJudgement() throws Exception {
        Date at = FMT.parse("2026-09-25 00:00:00");
        for (String bad : new String[] {"-0.01", "-1", "-100"}) {
            JudgeResult r = service.judge(new BigDecimal(bad), at);
            assertTrue(r.isBadInput());
            assertFalse(r.isAvailable());
            assertNull(r.getLevel());
            assertEquals(0, service.evaluateTop(new BigDecimal(bad), at));
        }
        JudgeResult huge = service.judge(new BigDecimal("1000"), at);
        assertTrue(huge.isBadInput(), "1000 米数量级明显不对");
        assertNull(huge.getLevel());
        // 边界：100 米仍是合法入参（照常判，超线归 4），100.01 不收
        assertFalse(service.judge(new BigDecimal("100"), at).isBadInput());
        assertTrue(service.judge(new BigDecimal("100.01"), at).isBadInput());
    }

    // 8. 阈值磕坏的迁移版本整版不收：好版本在就取好的，只有坏版就直回无可用判线
    @Test
    void dirtyThresholdVersion_skipped() throws Exception {
        Date at = FMT.parse("2026-09-25 00:00:00");
        // 倒挂脏版，priority 再高也不收
        put(300L, "BADLINE", "倒挂线", "9.00", "4.00", "6.00",
                "2026-09-01 00:00:00", "2026-12-01 00:00:00", 999, 0);
        assertFalse(service.usable(300L, at));
        assertTrue(service.listAvailable(at).stream().noneMatch(r -> r.getId() == 300L));

        // 负阈值脏版：同一代号只有它时，不拿它顶
        put(301L, "ONLYBAD", "负阈值线", "-1.00", "4.00", "6.00",
                "2026-09-01 00:00:00", "2026-12-01 00:00:00", 999, 0);
        JudgeResult r = service.judge("ONLYBAD", new BigDecimal("2"), at);
        assertFalse(r.isAvailable());
        assertEquals("当下无可用判线", r.getReason());
    }

    // 9. 生效起点含：新版生效那一秒就能取
    @Test
    void effStart_inclusive() throws Exception {
        Date start = FMT.parse("2026-10-01 00:00:00");
        assertEquals(203L, service.judge("COMBINED", new BigDecimal("3"), start).getRuleId());
        assertTrue(service.usable(203L, start));
    }

    // 10. 列表/计数/可用性同源；at 缺省不炸
    @Test
    void listCountUsable_sameSource_nullAtTolerated() throws Exception {
        Date at = FMT.parse("2026-09-25 00:00:00");
        List<TDrainLevelStd> list = service.listAvailable(at);
        List<Long> ids = new ArrayList<>();
        list.forEach(r -> ids.add(r.getId()));
        assertEquals(list.size(), service.countAvailable(at));
        // 头版必须是 priority=90 的应急线，作废线/空档版本不在列
        assertEquals(205L, list.get(0).getId());
        assertTrue(ids.contains(201L));
        assertTrue(ids.contains(210L));
        assertFalse(ids.contains(204L));
        assertFalse(ids.contains(202L));
        assertFalse(ids.contains(203L));
        assertTrue(service.usable(201L, at));
        // at 为空按当下：不抛异常即可（底账窗口未必覆盖真实时钟）
        service.listAvailable(null);
        service.countAvailable(null);
    }

    // 11. JudgeResult 没有任何档位写入口：判定出来的档不让人代填
    @Test
    void judgeResult_hasNoLevelSetter() {
        boolean hasSetter = false;
        for (Method m : JudgeResult.class.getMethods()) {
            if (m.getName().startsWith("set")) {
                hasSetter = true;
            }
        }
        assertFalse(hasSetter, "判定回话只读，外部拿不到任何 set 口子");
    }
}
