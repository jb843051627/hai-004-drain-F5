package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

import com.fc.v2.common.exception.DrainDispatchException;
import com.fc.v2.mapper.auto.TDrainDispatchBillMapper;
import com.fc.v2.mapper.auto.TDrainDispatchSignMapper;
import com.fc.v2.model.auto.TDrainDispatchBill;
import com.fc.v2.model.auto.TDrainDispatchSign;

/**
 * 强排调度三节点签批红线测试（内存库模拟两张表，计数口径与生产一致：只信流水实笔）。
 *
 * @author fuce
 * @date 2026-09-24
 */
class TDrainDispatchBillServiceImplTest {

    private static final String DUTY = "duty_lead01";
    private static final String KE_ZHANG = "drain_ke_zhang";
    private static final String KE_WANG = "drain_ke_wang";
    private static final String KE_LI = "drain_ke_li";
    private static final String BOSS = "boss_zhang";
    private static final String OUTSIDER = "someone_else";

    private TDrainDispatchBillServiceImpl service;
    private final Map<Long, TDrainDispatchBill> bills = new HashMap<>();
    private final Map<Long, TDrainDispatchSign> signs = new HashMap<>();
    private final AtomicLong billSeq = new AtomicLong(1000);
    private final AtomicLong signSeq = new AtomicLong(5000);

    @BeforeEach
    void setUp() {
        bills.clear();
        signs.clear();
        TDrainDispatchBillMapper billMapper = mock(TDrainDispatchBillMapper.class);
        TDrainDispatchSignMapper signMapper = mock(TDrainDispatchSignMapper.class);

        when(billMapper.insert(any())).thenAnswer(inv -> {
            TDrainDispatchBill b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId(billSeq.incrementAndGet());
            }
            bills.put(b.getId(), b);
            return 1;
        });
        when(billMapper.updateById(any())).thenAnswer(inv -> {
            TDrainDispatchBill b = inv.getArgument(0);
            bills.put(b.getId(), b);
            return 1;
        });
        when(billMapper.selectById(anyLong())).thenAnswer(inv -> bills.get(inv.getArgument(0)));
        when(billMapper.selectByIdForUpdate(anyLong())).thenAnswer(inv -> bills.get(inv.getArgument(0)));
        when(billMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(bills.values()));

        when(signMapper.insert(any())).thenAnswer(inv -> {
            TDrainDispatchSign s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(signSeq.incrementAndGet());
            }
            signs.put(s.getId(), s);
            return 1;
        });
        when(signMapper.listRoundNode(anyLong(), anyInt(), anyInt())).thenAnswer(inv -> {
            long billId = inv.getArgument(0);
            int round = inv.getArgument(1);
            int node = inv.getArgument(2);
            List<TDrainDispatchSign> hit = new ArrayList<>();
            for (TDrainDispatchSign s : signs.values()) {
                if (s.getBillId() == billId && s.getRoundNo() == round && s.getNodeNo() == node) {
                    hit.add(s);
                }
            }
            hit.sort(Comparator.comparing(TDrainDispatchSign::getSignTime));
            return hit;
        });
        when(signMapper.countByBill(anyLong())).thenAnswer(inv -> {
            long billId = inv.getArgument(0);
            int n = 0;
            for (TDrainDispatchSign s : signs.values()) {
                if (s.getBillId() == billId) {
                    n++;
                }
            }
            return n;
        });
        when(signMapper.listTrail(anyLong())).thenAnswer(inv -> {
            long billId = inv.getArgument(0);
            List<TDrainDispatchSign> hit = new ArrayList<>();
            for (TDrainDispatchSign s : signs.values()) {
                if (s.getBillId() == billId) {
                    hit.add(s);
                }
            }
            hit.sort(Comparator.comparing(TDrainDispatchSign::getSeqNo).reversed());
            return hit;
        });

        service = new TDrainDispatchBillServiceImpl();
        ReflectionTestUtils.setField(service, "drainDispatchBillMapper", billMapper);
        ReflectionTestUtils.setField(service, "drainDispatchSignMapper", signMapper);
    }

    private TDrainDispatchBill submitOne() {
        TDrainDispatchBill form = new TDrainDispatchBill();
        form.setPumpScale(new BigDecimal("1200.00"));
        form.setStartTime(new Date(1757600000000L));
        return service.submit(form);
    }

    // 1. 完整走链：或签→会签3人→或签，末节点过即批准
    @Test
    void fullChainApprove_andFreezeScaleAndTime() {
        TDrainDispatchBill b = submitOne();
        Long id = b.getId();
        BigDecimal scale = b.getPumpScale();
        Date start = b.getStartTime();

        b = service.approve(id, DUTY, "同意上报");
        assertEquals(1, b.getNodeNo().intValue(), "值班长或签1笔即过，进排水科");
        assertEquals(0, b.getSignCount().intValue(), "进新节点计数归零，不带上一节点的数");
        assertEquals(3, b.getNeedCount().intValue());

        b = service.approve(id, KE_ZHANG, "科长同意");
        assertEquals(1, b.getNodeNo().intValue(), "会签第1笔不过节点");
        assertEquals(1, b.getSignCount().intValue());
        b = service.approve(id, KE_WANG, "老王同意");
        assertEquals(1, b.getNodeNo().intValue(), "会签第2笔仍不过节点");
        assertEquals(2, b.getSignCount().intValue());

        b = service.approve(id, KE_LI, "老李同意");
        assertEquals(2, b.getNodeNo().intValue(), "会签三人齐才过，进分管领导");
        assertEquals(0, b.getSignCount().intValue());

        b = service.approve(id, BOSS, "批准强排");
        assertEquals(1, b.getStatus().intValue(), "分管领导签完批准强排");
        assertEquals(scale, b.getPumpScale(), "批准后强排规模原样冻结");
        assertEquals(start, b.getStartTime(), "批准后开工时刻原样冻结");
    }

    // 2. 前节点没批，后节点不许抢跑；非当前节点名单的人一律挡
    @Test
    void noJumpAhead_previousNotDone() {
        Long id = submitOne().getId();
        DrainDispatchException ex = assertThrows(DrainDispatchException.class,
                () -> service.approve(id, KE_ZHANG, "抢跑"), "排水科在值班长之前签应被挡");
        assertTrue(ex.getMessage().contains("不许抢跑"));

        DrainDispatchException ex2 = assertThrows(DrainDispatchException.class,
                () -> service.approve(id, BOSS, "抢跑"), "分管领导更不许抢跑");
        assertTrue(ex2.getMessage().contains("不许抢跑"));

        DrainDispatchException ex3 = assertThrows(DrainDispatchException.class,
                () -> service.approve(id, OUTSIDER, "凑热闹"), "名单外的人不许签");
        assertTrue(ex3.getMessage().contains("指定签批人"));
        assertEquals(0, service.trail(id).size(), "被挡的抢跑一笔都不许落库");
    }

    // 3. 会签差一笔不算过；三人必须全部落名，不替人凑数
    @Test
    void cosign_missingOneNotPass() {
        Long id = submitOne().getId();
        service.approve(id, DUTY, "同意");
        service.approve(id, KE_ZHANG, "同意");
        service.approve(id, KE_WANG, "同意");

        TDrainDispatchBill b = service.selectTDrainDispatchBillById(id);
        assertEquals(1, b.getNodeNo().intValue(), "差老李1笔，节点不过");
        assertEquals(2, b.getSignCount().intValue(), "页面数=库里实笔数=2");
        assertEquals(2, service.trail(id).stream()
                .filter(s -> s.getNodeNo() == 1 && s.getAction() == 1).toArray().length);

        // 名单外的人补签不被接受，绝不能用第3笔"凑齐"
        assertThrows(DrainDispatchException.class, () -> service.approve(id, OUTSIDER, "替老李签"));
        assertEquals(1, service.selectTDrainDispatchBillById(id).getNodeNo().intValue());
    }

    // 4. 同一人同节点不许重复落名
    @Test
    void duplicateSign_rejected() {
        Long id = submitOne().getId();
        service.approve(id, DUTY, "同意");
        // 值班长已过节点到排水科；排水科的人重复签两次
        service.approve(id, KE_ZHANG, "第一次");
        DrainDispatchException ex = assertThrows(DrainDispatchException.class,
                () -> service.approve(id, KE_ZHANG, "第二次"), "同人同节点重复签应被挡");
        assertTrue(ex.getMessage().contains("已签过"));
        long zhangCnt = service.trail(id).stream().filter(s -> KE_ZHANG.equals(s.getApprover())).count();
        assertEquals(1, zhangCnt, "重复签一笔都不许多落");
    }

    // 5. 任一签批人驳回：单据终止，后面任何人不许再签
    @Test
    void reject_terminatesAndNoMoreSign() {
        Long id = submitOne().getId();
        service.approve(id, DUTY, "同意");
        service.approve(id, KE_ZHANG, "同意");
        service.reject(id, KE_WANG, "水位复核有疑义，驳回");

        TDrainDispatchBill b = service.selectTDrainDispatchBillById(id);
        assertEquals(2, b.getStatus().intValue(), "驳回即终止");

        assertThrows(DrainDispatchException.class, () -> service.approve(id, KE_LI, "还想签"),
                "同节点未签的人也不许再签");
        assertThrows(DrainDispatchException.class, () -> service.approve(id, BOSS, "领导特批"),
                "后面节点不许再签");
        assertThrows(DrainDispatchException.class, () -> service.reject(id, KE_LI, "再驳一次"),
                "驳回后连驳回都不许再落");

        long vetoCnt = service.trail(id).stream().filter(s -> s.getAction() == 2).count();
        assertEquals(1, vetoCnt, "驳回笔只此一支");
    }

    // 6. 批准强排：此路走到底关死，不许再签、不许重报
    @Test
    void approved_sealedForever() {
        Long id = submitOne().getId();
        service.approve(id, DUTY, "同意");
        service.approve(id, KE_ZHANG, "同意");
        service.approve(id, KE_WANG, "同意");
        service.approve(id, KE_LI, "同意");
        service.approve(id, BOSS, "批准");

        assertThrows(DrainDispatchException.class, () -> service.approve(id, BOSS, "再签"),
                "批准后不许再签");
        assertThrows(DrainDispatchException.class,
                () -> service.resubmit(id, new BigDecimal("999"), new Date(), "想改规模"),
                "批准后不许重报/改规模与开工时刻");
    }

    // 7. 驳回重报：进下一轮从上节点重走，上轮批数清零不携旧数，旧痕保留倒查
    @Test
    void resubmit_clearsOldCountsButKeepsTrail() {
        Long id = submitOne().getId();
        service.approve(id, DUTY, "一轮同意");
        service.approve(id, KE_ZHANG, "一轮科长同意");
        service.reject(id, KE_WANG, "一轮驳回");
        int trailRound1 = service.trail(id).size();
        assertEquals(3, trailRound1, "一轮留3笔（2批1驳）");

        BigDecimal newScale = new BigDecimal("650.00");
        Date newStart = new Date(1757700000000L);
        TDrainDispatchBill b = service.resubmit(id, newScale, newStart, "核清后重报");

        assertEquals(2, b.getRoundNo().intValue(), "进第二轮");
        assertEquals(0, b.getNodeNo().intValue(), "从值班长重走");
        assertEquals(0, b.getSignCount().intValue(), "上轮批过的数清零");
        assertEquals(newScale, b.getPumpScale(), "重报可重新填报规模");
        assertEquals(newStart, b.getStartTime(), "重报可重新填开工时刻");

        // 第二轮值班长签：计数只算第二轮，第一轮的 duty 笔不带进来
        b = service.approve(id, DUTY, "二轮同意");
        assertEquals(1, b.getNodeNo().intValue(), "二轮值班长签完进排水科");
        assertEquals(trailRound1, service.trail(id).size() - 1,
                "上一轮旧痕原样保留，新一轮在其后追加，倒着能查");

        // 同一人第二轮在值班长节点是新轮次的第一笔，不被当成重复签
        service.approve(id, KE_ZHANG, "二轮科长同意");
        TDrainDispatchSign latest = service.trail(id).get(0);
        assertEquals(2, latest.getRoundNo().intValue());
    }

    // 8. 迁移半截申请照实认笔：101 排水科已2人，认2差1，不替第三笔凑数
    @Test
    void migratedBill_recognizesExistingSigns() {
        seedMigratedBill101();
        TDrainDispatchBill b = service.selectTDrainDispatchBillById(101L);
        assertEquals(1, b.getNodeNo().intValue());
        assertEquals(2, b.getSignCount().intValue(), "迁进来的2笔照实认，页面数=库内数");
        assertEquals(0, b.getStatus().intValue(), "仍在审批中");

        // 补上指定名单里最后一人 drain_ke_li 才过节点
        b = service.approve(101L, KE_LI, "迁移后补签");
        assertEquals(2, b.getNodeNo().intValue(), "第3名指定人落名才过会签");

        // 100：值班长已批、卡在排水科0笔
        seedMigratedBill100();
        TDrainDispatchBill b100 = service.selectTDrainDispatchBillById(100L);
        assertEquals(1, b100.getNodeNo().intValue());
        assertEquals(0, b100.getSignCount().intValue(), "当前会签节点一笔没有，就是0，不继承前节点");
    }

    // 9. 全程页库一致：每一步 sign_count 都等于流水表按本轮本节点重算的数
    @Test
    void signCountAlwaysMatchesRowCount() {
        Long id = submitOne().getId();
        service.approve(id, DUTY, "同意");
        service.approve(id, KE_ZHANG, "同意");
        service.approve(id, KE_WANG, "同意");

        TDrainDispatchBill view = service.selectTDrainDispatchBillById(id);
        long dbCount = service.trail(id).stream()
                .filter(s -> s.getRoundNo() == 1 && s.getNodeNo() == 1 && s.getAction() == 1)
                .count();
        assertEquals(dbCount, view.getSignCount().longValue(), "页面看到的数与库存数逐笔一致");

        // 人为把镜像数改"花"，读路径必须以流水重算纠正，不信镜像
        TDrainDispatchBill dirty = bills.get(id);
        dirty.setSignCount(99);
        TDrainDispatchBill repaired = service.selectTDrainDispatchBillById(id);
        assertEquals(2, repaired.getSignCount().intValue(), "计数只信流水实笔，镜像被改花也不凑数");
    }

    // 10. 申报校验
    @Test
    void submit_validatesScaleAndTime() {
        TDrainDispatchBill bad = new TDrainDispatchBill();
        bad.setPumpScale(BigDecimal.ZERO);
        bad.setStartTime(new Date());
        assertThrows(DrainDispatchException.class, () -> service.submit(bad), "规模非正应拒");

        TDrainDispatchBill noTime = new TDrainDispatchBill();
        noTime.setPumpScale(new BigDecimal("100"));
        assertThrows(DrainDispatchException.class, () -> service.submit(noTime), "开工时刻空应拒");
    }

    // 11. 审批中不许重报，只有被驳回才能重报
    @Test
    void resubmit_onlyAfterReject() {
        Long id = submitOne().getId();
        service.approve(id, DUTY, "同意");
        assertThrows(DrainDispatchException.class,
                () -> service.resubmit(id, new BigDecimal("1"), new Date(), "x"), "审批中不许重报");
    }

    // 12. 留痕倒查：节点/人/时刻/批语逐笔可查，含驳回句
    @Test
    void trail_recordsWhoWhenWhat() {
        Long id = submitOne().getId();
        service.approve(id, DUTY, "半夜电话情况属实");
        service.reject(id, KE_ZHANG, "规模存疑，先驳");
        List<TDrainDispatchSign> trail = service.trail(id);
        assertEquals(2, trail.size());
        assertEquals("规模存疑，先驳", trail.get(0).getComment(), "倒序首笔为最近驳回");
        assertEquals(KE_ZHANG, trail.get(0).getApprover());
        assertEquals(1, trail.get(0).getNodeNo().intValue());
        assertNotNull(trail.get(0).getSignTime(), "时刻必须留");
        assertEquals("半夜电话情况属实", trail.get(1).getComment());
    }

    // 13. 不存在的单据
    @Test
    void missingBill_throws() {
        assertThrows(DrainDispatchException.class, () -> service.approve(404L, DUTY, "x"));
        assertNull(service.selectTDrainDispatchBillById(404L));
    }

    // ---- 迁移种子（与 drain_dispatch_seed.sql 同构）----
    private void seedMigratedBill101() {
        TDrainDispatchBill b = new TDrainDispatchBill();
        b.setId(101L);
        b.setBillNo("QP-OLD-002");
        b.setRoundNo(1);
        b.setNodeNo(1);
        b.setSignMode(1);
        b.setNeedCount(3);
        b.setSignCount(2);
        b.setPumpScale(new BigDecimal("800.00"));
        b.setStartTime(new Date(1757600000000L));
        b.setStatus(0);
        b.setDelFlag(0);
        bills.put(101L, b);
        long t = 1757500000000L;
        putSign(1011L, 101L, 1, 0, 0, DUTY, 1, "同意强排", t, 1);
        putSign(1012L, 101L, 1, 1, 1, KE_ZHANG, 1, "排水科同意", t + 1000, 2);
        putSign(1013L, 101L, 1, 1, 1, KE_WANG, 1, "同意", t + 2000, 3);
    }

    private void seedMigratedBill100() {
        TDrainDispatchBill b = new TDrainDispatchBill();
        b.setId(100L);
        b.setBillNo("QP-OLD-001");
        b.setRoundNo(1);
        b.setNodeNo(1);
        b.setSignMode(1);
        b.setNeedCount(3);
        b.setSignCount(0);
        b.setPumpScale(new BigDecimal("1200.00"));
        b.setStartTime(new Date(1757600000000L));
        b.setStatus(0);
        b.setDelFlag(0);
        bills.put(100L, b);
        putSign(1001L, 100L, 1, 0, 0, DUTY, 1, "同意上报", 1757500000000L, 1);
    }

    private void putSign(long id, long billId, int round, int node, int mode,
                         String approver, int action, String comment, long time, int seq) {
        TDrainDispatchSign s = new TDrainDispatchSign();
        s.setId(id);
        s.setBillId(billId);
        s.setRoundNo(round);
        s.setNodeNo(node);
        s.setSignMode(mode);
        s.setApprover(approver);
        s.setAction(action);
        s.setComment(comment);
        s.setSignTime(new Date(time));
        s.setSeqNo(seq);
        signs.put(id, s);
    }
}
