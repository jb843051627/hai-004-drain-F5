package com.fc.v2.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TDrainRescueOrder;
import com.fc.v2.model.auto.TDrainRescueStep;

/**
 * 抢险作业单 Service接口（state-machine 形状：五档流转，不提供自由增删改查）
 *
 * 只暴露：开单(接单)、推进一档、撤回一档重办、退回档重办、销号后开补单、
 * 单面留痕与倒查留痕。销号是终态——没有改单、删单入口，确有错漏只能开补单。
 *
 * @author fuce
 * @date 2026-09-27
 */
public interface ITDrainRescueOrderService {

    /** 按主键查询单据（已删除视为无单） */
    TDrainRescueOrder selectTDrainRescueOrderById(Long id);

    /** 列表查询（流转台账，只摆未删单） */
    List<TDrainRescueOrder> selectTDrainRescueOrderList(QueryWrapper<TDrainRescueOrder> queryWrapper);

    /** 开单即接单：落到档0在办，接单这笔（经办人+时刻+接了什么活）当场进流水 */
    TDrainRescueOrder open(String siteNo, String operator, String detail);

    /**
     * 推进一档：只许 +1 不跨档；目标档已有现行办理笔（没撤回过）就是"同一档挪第二回"，拒。
     * 每挪一档把经办人与时刻落进流水；挪到档4即办结销号，终态关死。
     */
    TDrainRescueOrder advance(Long id, String operator, String detail);

    /**
     * 撤回一档重办：只许 -1；退回目标档及之后各档的现行办理笔一并作废
     * （作废笔不进单面，留库可倒查），单据置"退回重办"停在退回档。
     */
    TDrainRescueOrder rollback(Long id, String operator, String reason);

    /** 退回档重办：只在"退回重办"态可办，给退回档落一笔新办理（attempt_no+1），恢复在办 */
    TDrainRescueOrder redo(Long id, String operator, String detail);

    /**
     * 开补单：只对已销号单。母单一笔不动，新单挂 supplement_of 从接单重走五档。
     * 确有错漏只能走这里，不许在母单上动。
     */
    TDrainRescueOrder supplement(Long id, String operator, String detail);

    /** 单面留痕：只看现行有效笔（作废笔不并排挂上单面），seq_no 正序 */
    List<TDrainRescueStep> liveTrail(Long orderId);

    /** 倒查留痕：seq_no 倒序，作废笔一并带出，一级级倒着盘 */
    List<TDrainRescueStep> trail(Long orderId);
}
