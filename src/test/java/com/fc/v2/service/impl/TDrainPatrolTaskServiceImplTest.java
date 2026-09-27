package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TDrainDutyShiftMapper;
import com.fc.v2.mapper.auto.TDrainPatrolTaskMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.mapper.auto.TDrainWorkOrderMapper;
import com.fc.v2.model.auto.TDrainDutyShift;
import com.fc.v2.model.auto.TDrainPatrolTask;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.model.custom.PatrolRoundResult;

/**
 * 汛期值守巡检自动派单红线测试（内存库模拟表，口径与生产 SQL 一致）：
 * 空档不派、时段内派给当班人、一人一时段一站、一站不两人轮跟、压修单后推、
 * 派掉挂记号不再进候选、坏行只跳该行、空轮正常返回。
 *
 * @author fuce
 * @date 2026-09-26
 */
class TDrainPatrolTaskServiceImplTest {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private TDrainPatrolTaskServiceImpl service;
    private final Map<Long, TDrainPatrolTask> tasks = new LinkedHashMap<>();
    private final Map<Long, TDrainDutyShift> shifts = new LinkedHashMap<>();
    private final Map<String, TDrainStation> stations = new HashMap<>();
    private final Set<String> openOrderSites = new HashSet<>();
    private final AtomicLong seq = new AtomicLong(1000);

    private Date at;

    @BeforeEach
    void setUp() throws Exception {
        tasks.clear();
        shifts.clear();
        stations.clear();
        openOrderSites.clear();
        seq.set(1000);
        at = FMT.parse("2026-09-26 22:30:00");

        TDrainPatrolTaskMapper taskMapper = mock(TDrainPatrolTaskMapper.class);
        TDrainDutyShiftMapper shiftMapper = mock(TDrainDutyShiftMapper.class);
        TDrainStationMapper stationMapper = mock(TDrainStationMapper.class);
        TDrainWorkOrderMapper workOrderMapper = mock(TDrainWorkOrderMapper.class);

        when(taskMapper.selectById(anyLong())).thenAnswer(inv -> tasks.get(inv.getArgument(0)));
        when(taskMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(tasks.values()));
        when(taskMapper.updateById(any())).thenAnswer(inv -> {
            TDrainPatrolTask t = inv.getArgument(0);
            tasks.put(t.getId(), t);
            return 1;
        });
        // 与 markDispatched CAS + 两个唯一键同口径：仅待派可落已派，撞重抛唯一键
        when(taskMapper.markDispatched(anyLong(), anyString(), anyString(), anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            String assignee = inv.getArgument(1);
            String dispatchedAt = inv.getArgument(2);
            Long shiftId = inv.getArgument(3);
            TDrainPatrolTask cur = tasks.get(id);
            if (cur == null || !Integer.valueOf(0).equals(cur.getStatus())
                    || !Integer.valueOf(0).equals(cur.getDelFlag())) {
                return 0;
            }
            boolean userClash = tasks.values().stream().anyMatch(x ->
                    Integer.valueOf(1).equals(x.getStatus()) && shiftId.equals(x.getShiftId())
                            && assignee.equals(x.getAssignee()));
            boolean siteClash = tasks.values().stream().anyMatch(x ->
                    Integer.valueOf(1).equals(x.getStatus()) && shiftId.equals(x.getShiftId())
                            && cur.getSiteNo() != null && cur.getSiteNo().equals(x.getSiteNo()));
            if (userClash || siteClash) {
                throw new org.springframework.dao.DuplicateKeyException(
                        userClash ? "uk_shift_assignee" : "uk_shift_site");
            }
            cur.setStatus(1);
            cur.setAssignee(assignee);
            cur.setDispatchedAt(FMT.parse(dispatchedAt));
            cur.setShiftId(shiftId);
            return 1;
        });
        // 与 listWaitingDue SQL 同口径：未删、待派、到期已到（含恰好到期），按到期、id 升序
        when(taskMapper.listWaitingDue(anyString())).thenAnswer(inv -> {
            String now = inv.getArgument(0);
            return tasks.values().stream()
                    .filter(t -> Integer.valueOf(0).equals(t.getDelFlag()))
                    .filter(t -> Integer.valueOf(0).equals(t.getStatus()))
                    .filter(t -> t.getDueAt() != null && FMT.format(t.getDueAt()).compareTo(now) <= 0)
                    .sorted(Comparator.comparing(TDrainPatrolTask::getDueAt)
                            .thenComparing(TDrainPatrolTask::getId))
                    .collect(Collectors.toList());
        });
        when(taskMapper.countUserBusy(anyLong(), anyString())).thenAnswer(inv -> {
            Long shiftId = inv.getArgument(0);
            String user = inv.getArgument(1);
            return (int) tasks.values().stream()
                    .filter(t -> Integer.valueOf(0).equals(t.getDelFlag()))
                    .filter(t -> Integer.valueOf(1).equals(t.getStatus()))
                    .filter(t -> shiftId.equals(t.getShiftId()) && user.equals(t.getAssignee()))
                    .count();
        });
        when(taskMapper.countSiteTaken(anyLong(), anyString())).thenAnswer(inv -> {
            Long shiftId = inv.getArgument(0);
            String site = inv.getArgument(1);
            return (int) tasks.values().stream()
                    .filter(t -> Integer.valueOf(0).equals(t.getDelFlag()))
                    .filter(t -> Integer.valueOf(1).equals(t.getStatus()))
                    .filter(t -> shiftId.equals(t.getShiftId()) && site.equals(t.getSiteNo()))
                    .count();
        });

        when(shiftMapper.listCovering(anyString())).thenAnswer(inv -> {
            String now = inv.getArgument(0);
            return shifts.values().stream()
                    .filter(s -> Integer.valueOf(0).equals(s.getStatus()))
                    .filter(s -> Integer.valueOf(0).equals(s.getDelFlag()))
                    .filter(s -> FMT.format(s.getStartAt()).compareTo(now) <= 0
                            && FMT.format(s.getEndAt()).compareTo(now) > 0)
                    .sorted(Comparator.comparing(TDrainDutyShift::getStartAt)
                            .thenComparing(TDrainDutyShift::getId))
                    .collect(Collectors.toList());
        });

        when(stationMapper.selectOne(any())).thenAnswer(inv -> {
            // 测试里只按 site_no 建档，del_flag 均为 0；先渲染 SQL 段把懒加载的绑定参数物化
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            w.getSqlSegment();
            String siteNo = (String) w.getParamNameValuePairs().values().stream()
                    .filter(v -> v instanceof String && stations.containsKey(v))
                    .findFirst().orElse(null);
            return siteNo == null ? null : stations.get(siteNo);
        });

        when(workOrderMapper.countOpenBySite(anyString())).thenAnswer(inv ->
                openOrderSites.contains(inv.getArgument(0)) ? 1 : 0);

        service = new TDrainPatrolTaskServiceImpl();
        ReflectionTestUtils.setField(service, "drainPatrolTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "drainDutyShiftMapper", shiftMapper);
        ReflectionTestUtils.setField(service, "drainStationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "drainWorkOrderMapper", workOrderMapper);
    }

    private TDrainDutyShift shift(long id, String name, String user, String start, String end)
            throws Exception {
        TDrainDutyShift s = new TDrainDutyShift();
        s.setId(id);
        s.setShiftName(name);
        s.setDutyUser(user);
        s.setStartAt(FMT.parse(start));
        s.setEndAt(FMT.parse(end));
        s.setStatus(0);
        s.setDelFlag(0);
        shifts.put(id, s);
        return s;
    }

    private TDrainStation station(String siteNo, int status) {
        TDrainStation s = new TDrainStation();
        s.setId((long) siteNo.hashCode());
        s.setSiteNo(siteNo);
        s.setSiteName(siteNo + "号泵站");
        s.setStatus(status);
        s.setDelFlag(0);
        stations.put(siteNo, s);
        return s;
    }

    private TDrainPatrolTask task(String itemNo, String siteNo, String dueAt) throws Exception {
        long id = seq.incrementAndGet();
        TDrainPatrolTask t = new TDrainPatrolTask();
        t.setId(id);
        t.setItemNo(itemNo);
        t.setSiteNo(siteNo);
        t.setDueAt(FMT.parse(dueAt));
        t.setAmount(new BigDecimal("1"));
        t.setStatus(0);
        t.setDelFlag(0);
        tasks.put(id, t);
        return t;
    }

    // 1. 排班空档：一条不派、不吭声，条目原样待派，也不算异常
    @Test
    void noShiftCovering_idleRoundDispatchesNothing() throws Exception {
        TDrainPatrolTask t = task("XJ01", "PS00", "2026-09-26 20:00:00");
        station("PS00", 0);

        PatrolRoundResult r = service.dispatchRound(at);

        assertFalse(r.isDutyPeriod(), "空档时段 dutyPeriod=false");
        assertEquals(0, r.getDispatchedCount());
        assertEquals(0, r.getCandidateCount(), "空档连候选都不摆");
        assertEquals(0, t.getStatus().intValue(), "条目原样留着，不挂任何记号");
        assertNull(t.getAssignee());
    }

    // 2. 值守时段内：到点条目派给当班人，挂齐记号
    @Test
    void inDutyPeriod_dispatchesToDutyUser() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask t = task("XJ01", "PS00", "2026-09-26 22:00:00");
        station("PS00", 0);

        PatrolRoundResult r = service.dispatchRound(at);

        assertTrue(r.isDutyPeriod());
        assertEquals(1, r.getDispatchedCount());
        assertEquals(1, t.getStatus().intValue(), "已派");
        assertEquals("duty_wang", t.getAssignee());
        assertEquals(1L, t.getShiftId().longValue());
        assertNotNull(t.getDispatchedAt(), "派定时刻落记号");
        assertEquals("PS00", r.getDispatched().get(0).getSiteNo());
    }

    // 3. 恰好到期（due_at == 执行时刻）也算到点，要派
    @Test
    void dueExactlyAtExecutionTime_isDispatched() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask t = task("XJ01", "PS00", "2026-09-26 22:30:00");
        station("PS00", 0);

        assertEquals(1, service.dispatchRound(at).getDispatchedCount());
        assertEquals(1, t.getStatus().intValue());
    }

    // 4. 还没到点的条目不进候选
    @Test
    void notDueYet_skipped() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask t = task("XJ01", "PS00", "2026-09-26 23:00:00");
        station("PS00", 0);

        PatrolRoundResult r = service.dispatchRound(at);
        assertEquals(0, r.getDispatchedCount());
        assertEquals(0, r.getCandidateCount(), "23点才到期，22:30不进候选");
        assertEquals(0, t.getStatus().intValue());
    }

    // 5. 一人一时段最多一座站：同轮第二条（别的站）不再派给同一当班人，记后推
    @Test
    void oneUserOneStationPerShift_secondStationDeferred() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask t1 = task("XJ01", "PS00", "2026-09-26 21:00:00");
        TDrainPatrolTask t2 = task("XJ02", "PS02", "2026-09-26 21:30:00");
        station("PS00", 0);
        station("PS02", 0);

        PatrolRoundResult r = service.dispatchRound(at);

        assertEquals(1, r.getDispatchedCount(), "只派出一座站");
        assertEquals(1, t1.getStatus().intValue());
        assertEquals("duty_wang", t1.getAssignee());
        assertEquals(0, t2.getStatus().intValue(), "第二条保持待派，本轮后推");
        assertNull(t2.getAssignee());
        assertEquals(1, r.getDeferredCount());
        assertTrue(r.getDeferred().get(0).getReason().contains("已接一座站"));
    }

    // 6. 一座站同一时段不许两人轮着跟：同站第二条不再派
    @Test
    void oneStationNotFollowedByTwo_sameStationSecondDeferred() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask t1 = task("XJ01", "PS00", "2026-09-26 21:00:00");
        TDrainPatrolTask t2 = task("XJ02", "PS00", "2026-09-26 21:30:00");
        station("PS00", 0);

        PatrolRoundResult r = service.dispatchRound(at);

        assertEquals(1, r.getDispatchedCount());
        assertEquals(1, t1.getStatus().intValue());
        assertEquals(0, t2.getStatus().intValue(), "同站第二条不许换个轮次再派给人");
        // 两条同站同当班人：第一条先占人也占站，第二条原由点出本站已有主或人已接站均可
        assertEquals(1, r.getDeferredCount());
    }

    // 7. 站里压着没销的修单：本轮自动后推，条目保持待派；修单销了下轮自然择上
    @Test
    void openWorkOrder_defersThisRound_thenDispatchesAfterClosed() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask t = task("XJ01", "PS00", "2026-09-26 21:00:00");
        station("PS00", 0);
        openOrderSites.add("PS00");

        PatrolRoundResult r1 = service.dispatchRound(at);
        assertEquals(0, r1.getDispatchedCount());
        assertEquals(1, r1.getDeferredCount());
        assertEquals(0, t.getStatus().intValue(), "后推不挂记号，仍是待派");
        assertTrue(r1.getDeferred().get(0).getReason().contains("修单"));

        // 修单办结销项，下一轮同一班次该站就能派（人这轮还没接站）
        openOrderSites.remove("PS00");
        PatrolRoundResult r2 = service.dispatchRound(at);
        assertEquals(1, r2.getDispatchedCount());
        assertEquals(1, t.getStatus().intValue());
    }

    // 8. 派掉的挂记号，往后任何一轮不再进候选（跨轮次）
    @Test
    void dispatchedNeverReentersCandidates() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask t = task("XJ01", "PS00", "2026-09-26 21:00:00");
        station("PS00", 0);

        assertEquals(1, service.dispatchRound(at).getDispatchedCount());
        // 再跑两轮：已派条目不会被重新挑进候选（内存库模拟 status=0 过滤）
        assertEquals(0, service.dispatchRound(at).getDispatchedCount());
        assertEquals(0, service.dispatchRound(at).getDispatchedCount());
        assertEquals(1, t.getStatus().intValue());
        assertEquals("duty_wang", t.getAssignee(), "受派人不被后轮改写");
    }

    // 9. 坏行只跳过它自己：泵站编号缺失的留坏账，别的好行照派
    @Test
    void badRowSkipped_othersStillDispatch() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask bad = task("XJ-BAD", null, "2026-09-26 20:30:00");
        TDrainPatrolTask good = task("XJ01", "PS00", "2026-09-26 21:00:00");
        station("PS00", 0);

        PatrolRoundResult r = service.dispatchRound(at);

        assertEquals(1, r.getDispatchedCount(), "好行照派，不被坏行连坐");
        assertEquals(1, r.getBadRowCount());
        assertNull(r.getBadRows().get(0).getSiteNo());
        assertTrue(r.getBadRows().get(0).getReason().contains("泵站编号缺失"));
        assertEquals(0, bad.getStatus().intValue());
        assertEquals(1, good.getStatus().intValue());
    }

    // 10. 档案不存在/已停用也算坏行，跳过留账
    @Test
    void stationMissingOrDisabled_badRow() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask missing = task("XJ01", "PS404", "2026-09-26 20:30:00");
        TDrainPatrolTask disabled = task("XJ02", "PS01", "2026-09-26 21:00:00");
        station("PS01", 1);

        PatrolRoundResult r = service.dispatchRound(at);

        assertEquals(0, r.getDispatchedCount());
        assertEquals(2, r.getBadRowCount());
        assertEquals(0, missing.getStatus().intValue());
        assertEquals(0, disabled.getStatus().intValue());
    }

    // 11. 某轮一支都派不出是常事：时段内但无到期条目，正常回空账
    @Test
    void emptyRound_returnsNormally() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");

        PatrolRoundResult r = service.dispatchRound(at);

        assertTrue(r.isDutyPeriod());
        assertEquals(0, r.getCandidateCount());
        assertEquals(0, r.getDispatchedCount());
        assertEquals(0, service.runOnce(at), "runOnce 重载回派成条数 0，不抛异常");
    }

    // 12. runOnce() 无参重载与 dispatchRound() 以当前时刻跑通，不炸
    @Test
    void noArgOverloads_runWithoutError() {
        PatrolRoundResult r = service.dispatchRound();
        assertNotNull(r);
        assertEquals(0, service.runOnce());
    }

    // 13. 同刻多条：第一条压修单后推不占当班人，第二条（别的在用站、无修单）照常派出
    @Test
    void deferredByOrder_doesNotOccupyDutyUser() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask blocked = task("XJ01", "PS00", "2026-09-26 21:00:00");
        TDrainPatrolTask ok = task("XJ02", "PS02", "2026-09-26 21:30:00");
        station("PS00", 0);
        station("PS02", 0);
        openOrderSites.add("PS00");

        PatrolRoundResult r = service.dispatchRound(at);

        assertEquals(1, r.getDispatchedCount(), "压修单后推不占人，下一座站照派");
        assertEquals(1, r.getDeferredCount());
        assertEquals(0, blocked.getStatus().intValue());
        assertEquals(1, ok.getStatus().intValue());
        assertEquals("PS02", r.getDispatched().get(0).getSiteNo());
    }

    // 14. 同一时刻撞重排（两条有效排班）：锚定起时最早一条，另一条的人不参与，口径确定
    @Test
    void overlappingShifts_anchorEarliestDeterministically() throws Exception {
        shift(1, "夜班甲", "duty_li", "2026-09-26 18:00:00", "2026-09-27 02:00:00");
        shift(2, "夜班乙", "duty_zhao", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask t = task("XJ01", "PS00", "2026-09-26 21:00:00");
        station("PS00", 0);

        PatrolRoundResult r = service.dispatchRound(at);

        assertEquals(1L, r.getShiftId().longValue(), "锚定起时最早的班次");
        assertEquals("duty_li", r.getDutyUser());
        assertEquals("duty_li", t.getAssignee(), "不抽签、不轮给第二条排班的人");
    }

    // 15. 已派条目台账能查、待派条目也在（只读台账不依赖派单流程）
    @Test
    void listKeepsBothWaitingAndDispatched() throws Exception {
        shift(1, "夜班", "duty_wang", "2026-09-26 20:00:00", "2026-09-27 08:00:00");
        TDrainPatrolTask t1 = task("XJ01", "PS00", "2026-09-26 21:00:00");
        task("XJ02", "PS02", "2026-09-26 23:30:00");
        station("PS00", 0);
        station("PS02", 0);

        service.dispatchRound(at);

        List<TDrainPatrolTask> all = service.selectTDrainPatrolTaskList(new QueryWrapper<>());
        assertEquals(2, all.size());
        assertEquals(Arrays.asList("XJ01", "XJ02"),
                Arrays.asList(t1.getItemNo(), all.stream().filter(x -> "XJ02".equals(x.getItemNo()))
                        .findFirst().get().getItemNo()));
    }
}
