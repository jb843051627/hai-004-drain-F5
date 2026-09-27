package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TDrainWorkOrderMapper;
import com.fc.v2.model.auto.TDrainWorkOrder;

/**
 * 排涝抢险作业单状态机红线测试（内存库模拟表，口径与生产一致：环节逐档走、状态照字典）。
 *
 * @author fuce
 * @date 2026-09-25
 */
class TDrainWorkOrderServiceImplTest {

    /** 与实体字典对齐：0在办 1办结确认 2退回重办 */
    private static final int ACTIVE = 0;
    private static final int CLOSED = 1;
    private static final int RETURNED = 2;
    private static final int FINAL_STAGE = 4;

    private TDrainWorkOrderServiceImpl service;
    private final Map<Long, TDrainWorkOrder> orders = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        orders.clear();
        TDrainWorkOrderMapper mapper = mock(TDrainWorkOrderMapper.class);

        when(mapper.updateById(any())).thenAnswer(inv -> {
            TDrainWorkOrder o = inv.getArgument(0);
            orders.put(o.getId(), o);
            return 1;
        });
        when(mapper.selectById(anyLong())).thenAnswer(inv -> {
            TDrainWorkOrder o = orders.get(inv.getArgument(0));
            return (o == null || (o.getDelFlag() != null && o.getDelFlag() == 1)) ? null : o;
        });
        when(mapper.selectByIdForUpdate(anyLong())).thenAnswer(inv -> {
            TDrainWorkOrder o = orders.get(inv.getArgument(0));
            return (o == null || (o.getDelFlag() != null && o.getDelFlag() == 1)) ? null : o;
        });
        when(mapper.selectList(any())).thenAnswer(inv -> {
            List<TDrainWorkOrder> hit = new ArrayList<>();
            // service 统一补 del_flag=0，这里照该口径过滤
            for (TDrainWorkOrder o : orders.values()) {
                if (o.getDelFlag() == null || o.getDelFlag() == 0) {
                    hit.add(o);
                }
            }
            return hit;
        });

        service = new TDrainWorkOrderServiceImpl();
        ReflectionTestUtils.setField(service, "drainWorkOrderMapper", mapper);
    }

    private TDrainWorkOrder seed(int stage, int status) {
        long id = seq.incrementAndGet();
        TDrainWorkOrder o = new TDrainWorkOrder();
        o.setId(id);
        o.setBizNo("RX" + id);
        o.setStage(stage);
        o.setStatus(status);
        o.setDelFlag(0);
        orders.put(id, o);
        return o;
    }

    // 1. 逐档推进：每一档都有人把守，一次只走一档；不走满 0..4 不许办结
    @Test
    void advance_oneStageAtATime_closedOnlyAtFinal() {
        long id = seed(0, ACTIVE).getId();

        for (int expect = 1; expect < FINAL_STAGE; expect++) {
            TDrainWorkOrder o = service.advance(id, "推进");
            assertEquals(expect, o.getStage().intValue(), "一次只进一档");
            assertEquals(ACTIVE, o.getStatus().intValue(), "中间环节一律在办，不许提前显示办结");
        }

        TDrainWorkOrder done = service.advance(id, "末档确认");
        assertEquals(FINAL_STAGE, done.getStage().intValue());
        assertEquals(CLOSED, done.getStatus().intValue(), "走满到末档才办结确认");
    }

    // 2. 中间环节绝不能显示"已通过/办结"
    @Test
    void middleStage_neverShowsClosed() {
        long id = seed(2, ACTIVE).getId();
        TDrainWorkOrder o = service.advance(id, "x");
        assertEquals(3, o.getStage().intValue());
        assertEquals(ACTIVE, o.getStatus().intValue());
    }

    // 3. 办结即关死：推进/退回/改记/删除一律被拒
    @Test
    void closed_sealsEverything() {
        long id = seed(FINAL_STAGE, CLOSED).getId();

        assertNull(service.advance(id, "再推"), "办结后不许再推进");
        assertNull(service.rollback(id, "想退"), "办结后不许退回");
        assertFalse(service.updateContent(id, "偷偷改一笔"), "办结后不许改推进记录");
        assertFalse(service.remove(id), "办结留档，不许删除");
        assertEquals(FINAL_STAGE, orders.get(id).getStage().intValue(), "被拒后状态原样不动");
        assertEquals(CLOSED, orders.get(id).getStatus().intValue());
    }

    // 4. 退回只回一档、置"退回重办"停住；重新提交只从退回档进一档，不携旧进度
    @Test
    void rollback_oneStage_thenResubmitAdvancesJustOne() {
        long id = seed(3, ACTIVE).getId();

        TDrainWorkOrder back = service.rollback(id, "本档复核不过，退回");
        assertEquals(2, back.getStage().intValue(), "只回一档，不一把撸回起点");
        assertEquals(RETURNED, back.getStatus().intValue(), "退回后挂退回重办，流程停住");

        // 重新提交：只从退回档(2)进一档到3，绝不直接满员到末档
        TDrainWorkOrder again = service.advance(id, "整改后重新提交");
        assertEquals(3, again.getStage().intValue(), "重交只进一档，旧进度不随行");
        assertEquals(ACTIVE, again.getStatus().intValue(), "重新提交恢复在办");

        // 仍须在环节3被把守，再推一次才到末档办结
        TDrainWorkOrder done = service.advance(id, "末档确认");
        assertEquals(FINAL_STAGE, done.getStage().intValue());
        assertEquals(CLOSED, done.getStatus().intValue());
    }

    // 5. 退回重办态不能直接办结：在环节3被退回后，不可能一步到4
    @Test
    void returned_cannotJumpToClose() {
        long id = seed(3, ACTIVE).getId();
        service.rollback(id, "驳");
        TDrainWorkOrder o = service.advance(id, "重交");
        assertEquals(3, o.getStage().intValue());
        assertEquals(ACTIVE, o.getStatus().intValue(), "重交后停在环节3在办，不是办结");
    }

    // 6. 起点环节无处可退
    @Test
    void rollback_atFirstStage_rejected() {
        long id = seed(0, ACTIVE).getId();
        assertNull(service.rollback(id, "没东西可退"));
        assertEquals(0, orders.get(id).getStage().intValue());
        assertEquals(ACTIVE, orders.get(id).getStatus().intValue());
    }

    // 7. 办结状态下退回也被挡（关死后不能翻案）
    @Test
    void rollback_whenClosed_rejected() {
        long id = seed(FINAL_STAGE, CLOSED).getId();
        assertNull(service.rollback(id, "结案后翻案"));
    }

    // 8. 在办单可改记、可逻辑删除；删除后详情查不到、台账不列出
    @Test
    void active_updateAndLogicalRemove() {
        long id = seed(1, ACTIVE).getId();
        assertTrue(service.updateContent(id, "现场补记一笔"));
        assertEquals("现场补记一笔", orders.get(id).getContent());

        assertTrue(service.remove(id), "在办单允许删除（逻辑删）");
        assertEquals(1, orders.get(id).getDelFlag().intValue(), "只打删除标记，记录留库可倒查");
        assertNull(service.selectTDrainWorkOrderById(id), "删除后详情查不到");
        assertTrue(service.selectTDrainWorkOrderList(new QueryWrapper<>()).stream()
                .noneMatch(o -> o.getId() == id), "删除单不进台账");
    }

    // 9. 推进/退回必须逐笔落到推进记录，真出岔子翻账翻得出来
    @Test
    void trail_isRecordedOnEachMove() {
        long id = seed(0, ACTIVE).getId();
        service.advance(id, "一档到位");
        service.rollback(id, "复核存疑");

        String content = orders.get(id).getContent();
        assertNotNull(content);
        assertTrue(content.contains("推进至环节1"), "推进留痕");
        assertTrue(content.contains("一档到位"), "批语留痕");
        assertTrue(content.contains("退回环节0重办"), "退回留痕");
        assertTrue(content.contains("复核存疑"), "退回批语留痕");
    }

    // 10. 不存在的单据：各操作安静拒绝，不抛异常
    @Test
    void missingOrder_rejectedQuietly() {
        assertNull(service.advance(404L, "x"));
        assertNull(service.rollback(404L, "x"));
        assertFalse(service.updateContent(404L, "x"));
        assertFalse(service.remove(404L));
        assertNull(service.selectTDrainWorkOrderById(404L));
    }
}
