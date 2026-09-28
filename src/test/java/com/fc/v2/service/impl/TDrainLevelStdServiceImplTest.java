package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fc.v2.mapper.auto.TDrainLevelStdMapper;
import com.fc.v2.model.auto.TDrainLevelStd;
import com.fc.v2.model.custom.LevelJudgeResult;

/**
 * 泵站起排判线红线测试（内存表）：
 * 半开有效期交接不重不漏、空档直回无可用判线、作废老线不许填、
 * 同刻叠版照 priority 取且两边取同一版、存量两档线照用、负数/离谱数量级不收。
 *
 * 内存数据与 doc/schema/drain_level_seed.sql 逐笔对齐。
 *
 * @author fuce
 * @date 2026-09-27
 */
class TDrainLevelStdServiceImplTest {

    private static final String CX = "LS-CX-001";
    private static final String HL = "LS-HL-001";

    private TDrainLevelStdServiceImpl service;
    private final Map<Long, TDrainLevelStd> rows = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        rows.clear();
        TDrainLevelStdMapper mapper = mock(TDrainLevelStdMapper.class);
        when(mapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(rows.values()));
        when(mapper.selectById(any())).thenAnswer(inv -> rows.get(inv.getArgument(0)));

        service = new TDrainLevelStdServiceImpl();
        ReflectionTestUtils.setField(service, "drainLevelStdMapper", mapper);

        seed(4001L, CX, "城东干季版", "2", "5", "8",
                "2025-10-01 00:00:00", "2026-05-01 00:00:00", 10, 0);
        seed(4002L, CX, "城东湿季版", "1.5", "3.5", "6",
                "2026-05-01 00:00:00", "2026-10-01 00:00:00", 10, 0);
        seed(4003L, CX, "汛期加密版", "1", "3", "5",
                "2026-06-01 00:00:00", "2026-09-01 00:00:00", 20, 0);
        seed(4004L, CX, "老办法版", "3", "6", "9",
                "2024-01-01 00:00:00", "2025-10-01 00:00:00", 1, 1);
        seed(4005L, HL, "合流存量两档", "1.5", "4.5", null,
                "2026-01-01 00:00:00", null, 5, 0);
    }

    // 1. 交接前一刻走干季版；交接那一瞬间老版已失效、新版刚生效——当下有效的只能有一版
    @Test
    void handoff_boundary_oneSecondBefore_dry_onInstant_wet() {
        LevelJudgeResult before = service.evaluate(CX, new BigDecimal("2.00"), "2026-04-30 23:59:59");
        assertTrue(before.isAvailable());
        assertEquals(4001L, before.getRuleId());
        assertEquals(1, before.getLevel(), "2.00 贴着干季一档上限，临界取一档");

        LevelJudgeResult on = service.evaluate(CX, new BigDecimal("2.00"), "2026-05-01 00:00:00");
        assertTrue(on.isAvailable());
        assertEquals(4002L, on.getRuleId(), "交接瞬间归湿季版");
        assertEquals(2, on.getLevel(), "同一水位 2.00 在湿季版已过一档线 1.50，落二档");

        assertEquals(1, service.listAvailable(date("2026-05-01 00:00:00")).stream()
                .filter(r -> CX.equals(r.getRuleCode())).count(), "交接瞬间同代号有效版本唯一");
    }

    // 2. 干季版失效后、湿季版生效前若有空档（人为造一段），哪版都不沾，直回无可用判线
    @Test
    void gap_noVersionAtThatInstant_noRule() {
        TDrainLevelStd wet = rows.get(4002L);
        wet.setEffStart(date("2026-05-02 00:00:00")); // 05-01 整天变成空档
        LevelJudgeResult r = service.evaluate(CX, new BigDecimal("3"), "2026-05-01 12:00:00");
        assertTrue(r.isAccepted());
        assertFalse(r.isAvailable());
        assertNull(r.getLevel(), "无可用判线时不许带档，更不许拿作废老线填");
        assertEquals(0, service.evaluate(CX, new BigDecimal("3"), date("2026-05-01 12:00:00")),
                "老签名同口径返回 0");
    }

    // 3. 同刻几版叠着照 priority 取优；单代号判定与取优列表第一版必须是同一版
    @Test
    void overlap_priorityWins_bothPathsSameVersion() {
        Date at = date("2026-07-15 08:00:00");
        LevelJudgeResult byCode = service.evaluate(CX, new BigDecimal("3.00"), "2026-07-15 08:00:00");
        LevelJudgeResult top = service.evaluateTop(new BigDecimal("3.00"), "2026-07-15 08:00:00");
        List<TDrainLevelStd> avail = service.listAvailable(at);

        assertEquals(4003L, byCode.getRuleId(), "加密版 priority=20 压过湿季版 10");
        assertEquals(4003L, avail.get(0).getId());
        assertEquals(avail.get(0).getId(), top.getRuleId(), "取优判定与可用列表第一版同一版");
        assertEquals(byCode.getRuleId(), top.getRuleId(), "两条判定路径取同一版");
        assertEquals(2, top.getLevel(), "3.00 贴着加密版二档上限");

        // 加密版 09-01 收口（失效不含），同一水位自然回湿季版：3.00<=3.50 仍落二档
        LevelJudgeResult after = service.evaluate(CX, new BigDecimal("3.00"), "2026-09-01 00:00:00");
        assertEquals(4002L, after.getRuleId());
        assertEquals(2, after.getLevel());
    }

    // 4. 已作废的老线：回判的时刻即便落在它当年的窗口里，也不许拿它填
    @Test
    void voidedRule_neverFills_evenInsideItsOldWindow() {
        LevelJudgeResult r = service.evaluate(CX, new BigDecimal("5"), "2025-05-01 08:00:00");
        assertTrue(r.isAccepted());
        assertFalse(r.isAvailable(), "那一刻只有作废老版 4004 覆盖，按无可用判线回");
        assertNull(r.getLevel());
        assertFalse(service.usable(4004L, date("2025-05-01 08:00:00")));
    }

    // 5. 早年只录到前两档的存量线照旧能用：缺三档不废线，高出二档归超档
    @Test
    void legacyTwoTierLine_stillUsable_overTopGoesFour() {
        LevelJudgeResult r1 = service.evaluate(HL, new BigDecimal("1.50"), "2026-09-27 08:00:00");
        assertEquals(4005L, r1.getRuleId());
        assertEquals(1, r1.getLevel());
        assertEquals(0, r1.getLevelLimit().compareTo(new BigDecimal("1.50")));

        LevelJudgeResult r2 = service.evaluate(HL, new BigDecimal("4.50"), "2026-09-27 08:00:00");
        assertEquals(2, r2.getLevel(), "临界取高档：等于二档上限仍判二档");
        assertEquals(0, r2.getLevelLimit().compareTo(new BigDecimal("4.50")));

        LevelJudgeResult r3 = service.evaluate(HL, new BigDecimal("4.51"), "2026-09-27 08:00:00");
        assertTrue(r3.isAvailable());
        assertEquals(4, r3.getLevel(), "三档未划线，高出二档归超档");
        assertNull(r3.getLevelLimit());
        assertTrue(service.usable(4005L, date("2026-09-27 08:00:00")), "eff_end 为空=长期有效");
    }

    // 6. 水位负数不收；数量级明显不对（三位数开外）不收；100 为可收边界
    @Test
    void negativeAndOutOfMagnitude_rejected_butBoundary100Accepted() {
        LevelJudgeResult neg = service.evaluate(CX, new BigDecimal("-0.01"), "2026-09-27 08:00:00");
        assertFalse(neg.isAccepted());

        LevelJudgeResult huge = service.evaluate(CX, new BigDecimal("500.00"), "2026-09-27 08:00:00");
        assertFalse(huge.isAccepted(), "500 米数量级离谱，整笔不收");
        assertNull(huge.getLevel());

        LevelJudgeResult over = service.evaluateTop(new BigDecimal("100.01"), "2026-09-27 08:00:00");
        assertFalse(over.isAccepted());

        LevelJudgeResult edge = service.evaluate(CX, new BigDecimal("100"), "2026-09-27 08:00:00");
        assertTrue(edge.isAccepted());
        assertTrue(edge.isAvailable());
        assertEquals(4, edge.getLevel());

        assertEquals(0, service.evaluate(CX, new BigDecimal("-1"), date("2026-09-27 08:00:00")),
                "老签名：越界输入返回 0");
    }

    // 7. 参数缺失/时刻坏格式不收；空代号不收
    @Test
    void missingParams_andBadTimeFormat_rejected() {
        assertFalse(service.evaluate(CX, null, "2026-09-27 08:00:00").isAccepted());
        assertFalse(service.evaluate("  ", new BigDecimal("1"), "2026-09-27 08:00:00").isAccepted());
        assertFalse(service.evaluate(CX, new BigDecimal("1"), "2026/09/27 08:00").isAccepted());
        assertFalse(service.evaluateTop(new BigDecimal("1"), (String) null) == null,
                "时刻留空按当下，不返回 null");
    }

    // 8. 不存在的代号：输入收下，但按无可用判线回
    @Test
    void unknownCode_noRule_notRejected() {
        LevelJudgeResult r = service.evaluate("LS-NOPE", new BigDecimal("1"), "2026-09-27 08:00:00");
        assertTrue(r.isAccepted());
        assertFalse(r.isAvailable());
    }

    // 9. 已删判线从可用集合剔除；countAvailable 随窗口/状态走
    @Test
    void deletedExcluded_countFollowsWindowAndStatus() {
        Date summer = date("2026-07-15 08:00:00");
        // 夏季：加密版 + 湿季版 + 存量合流 = 3
        assertEquals(3, service.countAvailable(summer));

        rows.get(4003L).setDelFlag(1);
        assertEquals(2, service.countAvailable(summer), "删掉加密版后剩湿季版+合流存量");
        assertEquals(4002L, service.evaluate(CX, new BigDecimal("3"), "2026-07-15 08:00:00").getRuleId());

        Date winter = date("2026-01-15 08:00:00");
        assertEquals(2, service.countAvailable(winter), "冬季在窗的是干季版+长期有效的合流存量线");
    }

    // 10. priority 并列时按 ruleCode、id 确定性兜底，取版结果稳定
    @Test
    void samePriority_tieBreaker_isDeterministic() {
        seed(4006L, CX, "并列同代号甲", "1", "2", "3",
                "2026-08-01 00:00:00", "2026-08-31 00:00:00", 20, 0);
        Date at = date("2026-08-10 08:00:00");
        // 4003 与 4006 同代号同 priority，id 大的在前
        TDrainLevelStd first = service.listAvailable(at).get(0);
        assertEquals(4006L, first.getId());
        assertEquals(first.getId(),
                service.evaluateTop(new BigDecimal("1.5"), "2026-08-10 08:00:00").getRuleId());
    }

    // 11. selectById 直查不走窗口（旁路台账用），但 usable 走窗口
    @Test
    void selectById_raw_usable_windowed() {
        assertSame(rows.get(4001L), service.selectTDrainLevelStdById(4001L));
        assertTrue(service.usable(4001L, date("2026-04-30 23:59:59")));
        assertFalse(service.usable(4001L, date("2026-05-01 00:00:00")), "失效不含边界");
        assertFalse(service.usable(4001L, null));
        assertFalse(service.usable(null, date("2026-04-30 23:59:59")));
        assertEquals(0, service.countAvailable(null));
        assertTrue(service.listAvailable(null).isEmpty());
    }

    // ---- 造数/解析小工具 ----

    private void seed(long id, String code, String name, String t1, String t2, String t3,
                      String start, String end, Integer priority, int status) {
        TDrainLevelStd r = new TDrainLevelStd();
        r.setId(id);
        r.setRuleCode(code);
        r.setRuleName(name);
        r.setTh1Max(t1 == null ? null : new BigDecimal(t1));
        r.setTh2Max(t2 == null ? null : new BigDecimal(t2));
        r.setTh3Max(t3 == null ? null : new BigDecimal(t3));
        r.setEffStart(date(start));
        r.setEffEnd(end == null ? null : date(end));
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(0);
        rows.put(id, r);
    }

    private static Date date(String text) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
