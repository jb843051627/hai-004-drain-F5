package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fc.v2.common.exception.DrainRescueException;
import com.fc.v2.mapper.auto.TDrainRescueOrderMapper;
import com.fc.v2.mapper.auto.TDrainRescueStepMapper;
import com.fc.v2.model.auto.TDrainRescueOrder;
import com.fc.v2.model.auto.TDrainRescueStep;

/**
 * 抢险作业单五档流转红线测试（内存库模拟两张表，口径与生产一致：
 * 一档一档挪、每档经办人时刻落流水、撤回目标档及之后作废不并排挂、销号关死只能补单）。
 *
 * @author fuce
 * @date 2026-09-27
 */
class TDrainRescueOrderServiceImplTest {

    /** 与实体字典对齐：0在办 1已销号 2退回重办 */
    private static final int ACTIVE = 0;
    private static final int CLOSED = 1;
    private static final int RETURNED = 2;
    private static final int FINAL_STAGE = 4;

    private TDrainRescueOrderServiceImpl service;
    private TDrainRescueStepMapper stepMapper;
    private final Map<Long, TDrainRescueOrder> orders = new HashMap<>();
    private final Map<Long, TDrainRescueStep> steps = new HashMap<>();
    private final AtomicLong orderSeq = new AtomicLong(3000);
    private final AtomicLong stepSeq = new AtomicLong(50000);

    @BeforeEach
    void setUp() {
        orders.clear();
        steps.clear();
        TDrainRescueOrderMapper orderMapper = mock(TDrainRescueOrderMapper.class);
        stepMapper = mock(TDrainRescueStepMapper.class);

        when(orderMapper.insert(any())).thenAnswer(inv -> {
            TDrainRescueOrder o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(orderSeq.incrementAndGet());
            }
            orders.put(o.getId(), o);
            return 1;
        });
        when(orderMapper.updateById(any())).thenAnswer(inv -> {
            TDrainRescueOrder o = inv.getArgument(0);
            orders.put(o.getId(), o);
            return 1;
        });
        when(orderMapper.selectById(anyLong())).thenAnswer(inv -> {
            TDrainRescueOrder o = orders.get(inv.getArgument(0));
            return (o == null || (o.getDelFlag() != null && o.getDelFlag() == 1)) ? null : o;
        });
        when(orderMapper.selectByIdForUpdate(anyLong())).thenAnswer(inv -> {
            TDrainRescueOrder o = orders.get(inv.getArgument(0));
            return (o == null || (o.getDelFlag() != null && o.getDelFlag() == 1)) ? null : o;
        });
        when(orderMapper.selectList(any())).thenAnswer(inv -> {
            List<TDrainRescueOrder> hit = new ArrayList<>();
            // service 统一补 del_flag=0，这里照该口径过滤
            for (TDrainRescueOrder o : orders.values()) {
                if (o.getDelFlag() == null || o.getDelFlag() == 0) {
                    hit.add(o);
                }
            }
            return hit;
        });

        when(stepMapper.insert(any())).thenAnswer(inv -> {
            TDrainRescueStep s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(stepSeq.incrementAndGet());
            }
            steps.put(s.getId(), s);
            return 1;
        });
        when(stepMapper.countByOrder(anyLong())).thenAnswer(inv -> {
            long orderId = inv.getArgument(0);
            int n = 0;
            for (TDrainRescueStep s : steps.values()) {
                if (s.getOrderId() == orderId) {
                    n++;
                }
            }
            return n;
        });
        when(stepMapper.maxAttempt(anyLong(), anyInt())).thenAnswer(inv -> {
            long orderId = inv.getArgument(0);
            int stage = inv.getArgument(1);
            int max = 0;
            for (TDrainRescueStep s : steps.values()) {
                if (s.getOrderId() == orderId && s.getStage() == stage
                        && s.getAttemptNo() != null && s.getAttemptNo() > max) {
                    max = s.getAttemptNo();
                }
            }
            return max;
        });
        when(stepMapper.getLive(anyLong(), anyInt())).thenAnswer(inv -> {
            long orderId = inv.getArgument(0);
            int stage = inv.getArgument(1);
            TDrainRescueStep hit = null;
            for (TDrainRescueStep s : steps.values()) {
                if (s.getOrderId() == orderId && s.getStage() == stage
                        && (s.getVoidFlag() == null || s.getVoidFlag() == 0)
                        && (hit == null || s.getId() > hit.getId())) {
                    hit = s;
                }
            }
            return hit;
        });
        when(stepMapper.voidFromStage(anyLong(), anyInt(), any(), any(), any())).thenAnswer(inv -> {
            long orderId = inv.getArgument(0);
            int fromStage = inv.getArgument(1);
            String voidBy = inv.getArgument(2);
            Date voidTime = inv.getArgument(3);
            String voidReason = inv.getArgument(4);
            int n = 0;
            for (TDrainRescueStep s : steps.values()) {
                if (s.getOrderId() == orderId && s.getStage() >= fromStage
                        && (s.getVoidFlag() == null || s.getVoidFlag() == 0)) {
                    s.setVoidFlag(1);
                    s.setVoidBy(voidBy);
                    s.setVoidTime(voidTime);
                    s.setVoidReason(voidReason);
                    n++;
                }
            }
            return n;
        });
        when(stepMapper.listLive(anyLong())).thenAnswer(inv -> {
            long orderId = inv.getArgument(0);
            List<TDrainRescueStep> hit = new ArrayList<>();
            for (TDrainRescueStep s : steps.values()) {
                if (s.getOrderId() == orderId && (s.getVoidFlag() == null || s.getVoidFlag() == 0)) {
                    hit.add(s);
                }
            }
            hit.sort(Comparator.comparing(TDrainRescueStep::getSeqNo));
            return hit;
        });
        when(stepMapper.listTrail(anyLong())).thenAnswer(inv -> {
            long orderId = inv.getArgument(0);
            List<TDrainRescueStep> hit = new ArrayList<>();
            for (TDrainRescueStep s : steps.values()) {
                if (s.getOrderId() == orderId) {
                    hit.add(s);
                }
            }
            hit.sort(Comparator.comparing(TDrainRescueStep::getSeqNo).reversed());
            return hit;
        });

        service = new TDrainRescueOrderServiceImpl();
        ReflectionTestUtils.setField(service, "drainRescueOrderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "drainRescueStepMapper", stepMapper);
    }

    private TDrainRescueOrder seed(int stage, int status) {
        return seed(stage, status, null);
    }

    private TDrainRescueOrder seed(int stage, int status, Long supplementOf) {
        TDrainRescueOrder o = new TDrainRescueOrder();
        o.setId(orderSeq.incrementAndGet());
        o.setBizNo("QX" + o.getId());
        o.setSiteNo("PS00");
        o.setStage(stage);
        o.setStatus(status);
        o.setSupplementOf(supplementOf);
        o.setDelFlag(0);
        orders.put(o.getId(), o);
        return o;
    }

    private void seedLiveStep(long orderId, String bizNo, int stage, int attempt, String operator) {
        TDrainRescueStep s = new TDrainRescueStep();
        s.setId(stepSeq.incrementAndGet());
        s.setOrderId(orderId);
        s.setBizNo(bizNo);
        s.setStage(stage);
        s.setAttemptNo(attempt);
        s.setOperator(operator);
        s.setAction(1);
        s.setDetail("第" + attempt + "回办理档" + stage);
        s.setOpTime(new Date());
        s.setSeqNo(steps.size() + 1);
        s.setVoidFlag(0);
        steps.put(s.getId(), s);
    }

    // 1. 开单即接单：落档0在办，接单这笔（经办人+时刻+接了什么）当场进流水
    @Test
    void open_landsOnAcceptStage_withAcceptStep() {
        TDrainRescueOrder o = service.open("PS00", "duty_li", "接交警转警：下穿隧道积水");

        assertEquals(0, o.getStage().intValue());
        assertEquals(ACTIVE, o.getStatus().intValue());
        assertNotNull(o.getBizNo());
        List<TDrainRescueStep> live = service.liveTrail(o.getId());
        assertEquals(1, live.size());
        TDrainRescueStep accept = live.get(0);
        assertEquals(0, accept.getStage().intValue());
        assertEquals(1, accept.getAttemptNo().intValue(), "首办回数1");
        assertEquals("duty_li", accept.getOperator(), "经办人写进单里");
        assertNotNull(accept.getOpTime(), "经办时刻写进单里");
    }

    // 2. 逐档推进：一次只走一档；不走满五档不许销号
    @Test
    void advance_oneStageAtATime_closedOnlyAtFinal() {
        long id = service.open("PS00", "duty_li", "接单").getId();

        for (int expect = 1; expect < FINAL_STAGE; expect++) {
            TDrainRescueOrder o = service.advance(id, "crew_zhang", "推进到档" + expect);
            assertEquals(expect, o.getStage().intValue(), "一次只进一档，不许跨档");
            assertEquals(ACTIVE, o.getStatus().intValue(), "中间各档一律在办");
        }
        TDrainRescueOrder done = service.advance(id, "duty_li", "销号确认");
        assertEquals(FINAL_STAGE, done.getStage().intValue());
        assertEquals(CLOSED, done.getStatus().intValue(), "走满到档4才办结销号");

        List<TDrainRescueStep> live = service.liveTrail(id);
        assertEquals(5, live.size(), "五档各一笔，每笔都有经办人与时刻");
        for (TDrainRescueStep s : live) {
            assertNotNull(s.getOperator());
            assertNotNull(s.getOpTime());
        }
    }

    // 3. 同一档不许挪第二回：目标档已有现行办理笔，再往该档挪即拒
    @Test
    void advance_intoStageAlreadyDone_rejected() {
        TDrainRescueOrder o = seed(1, ACTIVE);
        seedLiveStep(o.getId(), o.getBizNo(), 2, 1, "crew_zhang");

        DrainRescueException ex = assertThrows(DrainRescueException.class,
                () -> service.advance(o.getId(), "crew_wang", "想在处置档再挪一笔"));
        assertTrue(ex.getMessage().contains("同一档不许挪第二回"));
        assertEquals(1, orders.get(o.getId()).getStage().intValue(), "被拒后档位原样不动");
    }

    // 4. 办结销号即终态：推进/撤回/重办一律被拒；补单可以开，母单一笔不动
    @Test
    void closed_sealsEverything_butSupplementOpensNewOrder() {
        TDrainRescueOrder o = seed(FINAL_STAGE, CLOSED);
        seedLiveStep(o.getId(), o.getBizNo(), FINAL_STAGE, 1, "duty_li");

        assertThrows(DrainRescueException.class, () -> service.advance(o.getId(), "x", "再推"));
        assertThrows(DrainRescueException.class, () -> service.rollback(o.getId(), "x", "想退"));
        assertThrows(DrainRescueException.class, () -> service.redo(o.getId(), "x", "想重办"));
        assertEquals(FINAL_STAGE, orders.get(o.getId()).getStage().intValue(), "被拒后原样不动");
        assertEquals(CLOSED, orders.get(o.getId()).getStatus().intValue());

        TDrainRescueOrder sup = service.supplement(o.getId(), "duty_li", "销号后发现撤场时刻填错，开补单");
        assertEquals(o.getId(), sup.getSupplementOf(), "补单挂回母单");
        assertEquals(0, sup.getStage().intValue(), "补单从接单重走五档");
        assertEquals(ACTIVE, sup.getStatus().intValue());
        assertEquals(CLOSED, orders.get(o.getId()).getStatus(), "母单终态一笔不动");
        assertNull(orders.get(o.getId()).getSupplementOf(), "母单不被补单反向改写");
    }

    // 5. 撤回只回一档：退回目标档及之后各档现行笔一并作废，单据置退回重办停住
    @Test
    void rollback_oneStage_voidsTargetAndLater() {
        TDrainRescueOrder o = seed(3, ACTIVE);
        for (int st = 0; st <= 3; st++) {
            seedLiveStep(o.getId(), o.getBizNo(), st, 1, "crew_" + st);
        }

        TDrainRescueOrder back = service.rollback(o.getId(), "duty_li", "处置办砸，退回");
        assertEquals(2, back.getStage().intValue(), "只回一档，不一把撸回起点");
        assertEquals(RETURNED, back.getStatus().intValue(), "撤回后挂退回重办，流程停住");

        // 档2(处置)与档3(撤场)都作废；档0/1不动
        for (TDrainRescueStep s : steps.values()) {
            if (s.getOrderId() != o.getId()) {
                continue;
            }
            if (s.getStage() >= 2) {
                assertEquals(1, s.getVoidFlag().intValue(), "退回档及之后的旧笔一律作废");
                assertEquals("duty_li", s.getVoidBy(), "谁撤的写进作废笔");
                assertNotNull(s.getVoidTime(), "作废时刻写进作废笔");
                assertEquals("处置办砸，退回", s.getVoidReason());
            } else {
                assertEquals(0, s.getVoidFlag().intValue(), "退回档之前的记录不动");
            }
        }
    }

    // 6. 退回重办态不许直接推进：必须先在退回档重办补回经办记录
    @Test
    void returned_mustRedoBeforeAdvance() {
        TDrainRescueOrder o = seed(2, RETURNED);
        DrainRescueException ex = assertThrows(DrainRescueException.class,
                () -> service.advance(o.getId(), "crew_li", "不重办就想往下溜"));
        assertTrue(ex.getMessage().contains("先在") && ex.getMessage().contains("重办"));
        assertEquals(2, orders.get(o.getId()).getStage().intValue());
        assertEquals(RETURNED, orders.get(o.getId()).getStatus().intValue());
    }

    // 7. 重办落新笔（回数+1）：单面只见新笔，作废旧笔不并排挂；倒查新旧都翻得到
    @Test
    void redo_writesNewAttempt_voidNotOnBillFaceButAuditable() {
        TDrainRescueOrder o = seed(2, RETURNED);
        seedLiveStep(o.getId(), o.getBizNo(), 0, 1, "a0");
        seedLiveStep(o.getId(), o.getBizNo(), 1, 1, "a1");
        seedLiveStep(o.getId(), o.getBizNo(), 2, 1, "bad_crew");
        stepMapper.voidFromStage(o.getId(), 2, "duty_li", new Date(), "处置办砸");

        TDrainRescueOrder redone = service.redo(o.getId(), "good_crew", "处置重做完成");
        assertEquals(2, redone.getStage().intValue(), "重办不挪档，仍停在退回档");
        assertEquals(ACTIVE, redone.getStatus().intValue(), "重办后恢复在办");

        TDrainRescueStep live2 = stepMapper.getLive(o.getId(), 2);
        assertEquals(2, live2.getAttemptNo().intValue(), "重办是本档第2回");
        assertEquals("good_crew", live2.getOperator());
        assertEquals("处置重做完成", live2.getDetail());

        List<TDrainRescueStep> face = service.liveTrail(o.getId());
        assertEquals(3, face.size(), "单面：接单/布防/处置新笔，共三笔");
        assertTrue(face.stream().noneMatch(s -> "bad_crew".equals(s.getOperator())),
                "作废旧笔不许跟新记录并排挂在同一张单上");
        assertTrue(face.stream().allMatch(s -> s.getVoidFlag() == null || s.getVoidFlag() == 0));

        List<TDrainRescueStep> audit = service.trail(o.getId());
        assertEquals(4, audit.size(), "倒查：作废笔一并带出，一级级倒着盘");
        assertTrue(audit.stream().anyMatch(s -> "bad_crew".equals(s.getOperator())
                && s.getVoidFlag() == 1), "作废旧笔留库，翻得到是谁办砸、谁撤的");
    }

    // 8. 撤回→重办→再推进走全：其后各档也要重办（新回数），最终销号
    @Test
    void rollbackRedoThenAdvance_fullWalkWithNewAttempts() {
        long id = service.open("PS00", "duty_li", "接单").getId();
        service.advance(id, "c1", "布防");
        service.advance(id, "c2", "处置首办");
        service.advance(id, "c3", "撤场首办");
        // 撤场办砸：撤回到处置档（档2、档3两笔作废）
        service.rollback(id, "duty_li", "处置不到位连带撤场无效");

        service.redo(id, "c2b", "处置重办");
        service.advance(id, "c3b", "撤场重办");
        TDrainRescueOrder done = service.advance(id, "duty_li", "销号");
        assertEquals(FINAL_STAGE, done.getStage().intValue());
        assertEquals(CLOSED, done.getStatus().intValue());

        List<TDrainRescueStep> face = service.liveTrail(id);
        assertEquals(5, face.size(), "单面仍是一档一笔，五档齐");
        assertEquals(2, stepMapper.getLive(id, 2).getAttemptNo().intValue(), "处置是第2回");
        assertEquals(2, stepMapper.getLive(id, 3).getAttemptNo().intValue(), "撤场也是第2回");
        assertEquals(1, stepMapper.getLive(id, 4).getAttemptNo().intValue(), "销号首办");
        assertEquals(7, service.trail(id).size(), "倒查共七笔：两笔作废+五笔现行");
    }

    // 9. 撤回的边界：接单档无处可退；已停在退回档不许连着往下退
    @Test
    void rollback_boundaries() {
        long justOpened = service.open("PS00", "u", "接单").getId();
        assertThrows(DrainRescueException.class, () -> service.rollback(justOpened, "u", "没档可退"));
        assertEquals(0, orders.get(justOpened).getStage().intValue());

        TDrainRescueOrder returned = seed(1, RETURNED);
        assertThrows(DrainRescueException.class,
                () -> service.rollback(returned.getId(), "u", "连着退"));
        assertEquals(1, orders.get(returned.getId()).getStage().intValue());
    }

    // 10. 重办只接待退回重办态；在办单重办被拒
    @Test
    void redo_onlyWhenReturned() {
        TDrainRescueOrder o = seed(2, ACTIVE);
        assertThrows(DrainRescueException.class, () -> service.redo(o.getId(), "u", "在办中想重办"));
        assertEquals(ACTIVE, orders.get(o.getId()).getStatus().intValue());
    }

    // 11. 补单只补给销号单；在办/退回单有错漏走撤回重办
    @Test
    void supplement_onlyForClosed() {
        TDrainRescueOrder active = seed(2, ACTIVE);
        assertThrows(DrainRescueException.class,
                () -> service.supplement(active.getId(), "u", "在办想开补单"));
        TDrainRescueOrder returned = seed(1, RETURNED);
        assertThrows(DrainRescueException.class,
                () -> service.supplement(returned.getId(), "u", "退回中想开补单"));
    }

    // 12. 每挪一档都得写清谁办的、办了什么；空泵站不许开单
    @Test
    void missingOperatorOrDetail_rejected() {
        assertThrows(DrainRescueException.class, () -> service.open("PS00", null, "接单"));
        assertThrows(DrainRescueException.class, () -> service.open("PS00", "u", null));
        assertThrows(DrainRescueException.class, () -> service.open(null, "u", "接单"));

        long id = service.open("PS00", "u", "接单").getId();
        assertThrows(DrainRescueException.class, () -> service.advance(id, "  ", "布防"));
        assertThrows(DrainRescueException.class, () -> service.advance(id, "c", ""));
        assertThrows(DrainRescueException.class,
                () -> service.rollback(id, "u", "还在接单档，且缘由必填不影响边界"));

        TDrainRescueOrder o = seed(2, ACTIVE);
        assertThrows(DrainRescueException.class, () -> service.rollback(o.getId(), null, "缘由"));
        assertThrows(DrainRescueException.class, () -> service.rollback(o.getId(), "u", null));
    }

    // 13. 不存在的单据：各流转动作一律抛业务异常
    @Test
    void missingOrder_rejected() {
        assertThrows(DrainRescueException.class, () -> service.advance(404L, "u", "x"));
        assertThrows(DrainRescueException.class, () -> service.rollback(404L, "u", "x"));
        assertThrows(DrainRescueException.class, () -> service.redo(404L, "u", "x"));
        assertThrows(DrainRescueException.class, () -> service.supplement(404L, "u", "x"));
        assertNull(service.selectTDrainRescueOrderById(404L));
    }

    // 14. 台账只摆未删单；流水倒查按 seq_no 倒序，最近一笔在最前
    @Test
    void listHidesDeleted_trailIsDescending() {
        long id = service.open("PS00", "u", "接单").getId();
        service.advance(id, "u", "布防");
        TDrainRescueOrder deleted = seed(1, ACTIVE);
        deleted.setDelFlag(1);

        List<TDrainRescueOrder> list = service.selectTDrainRescueOrderList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        assertTrue(list.stream().anyMatch(o -> o.getId() == id));
        assertFalse(list.stream().anyMatch(o -> o.getId() == deleted.getId()), "删除单不进台账");
        assertNull(service.selectTDrainRescueOrderById(deleted.getId()), "删除后详情查不到");

        List<TDrainRescueStep> trail = service.trail(id);
        assertEquals(2, trail.size());
        assertEquals(1, trail.get(0).getStage().intValue(), "倒序：布防在接单前");
        assertEquals(0, trail.get(1).getStage().intValue());
    }
}
