package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.exception.DrainRescueException;
import com.fc.v2.mapper.auto.TDrainRescueOrderMapper;
import com.fc.v2.mapper.auto.TDrainRescueStepMapper;
import com.fc.v2.model.auto.TDrainRescueOrder;
import com.fc.v2.model.auto.TDrainRescueStep;
import com.fc.v2.model.custom.RescueStage;
import com.fc.v2.service.ITDrainRescueOrderService;

/**
 * 抢险作业单 Service业务层处理（state-machine 形状：五档一道一道走）
 *
 * 口径（与 t_drain_rescue_order / t_drain_rescue_step 列字典一致）：
 * 1. 档固定 0接单 1到场布防 2处置 3撤场 4办结销号，只许一档一档往前挪，不许跨档；
 *    同一档不重办就挪不出第二回——目标档已有现行办理笔即拒，库唯一键
 *    uk_order_stage_attempt 再兜一道；
 * 2. 每挪一档落一笔流水：哪一档、第几回办、谁办的、什么时刻、办了什么，
 *    倒查按 seq_no 倒序一级级盘；主表不冗余留痕；
 * 3. 办结销号(走到档4)即终态：不许再推进/撤回/重办，没有改单删单入口，
 *    确有错漏只能开补单（supplement_of 挂回母单），母单一笔不动；
 * 4. 撤回一档重办：退回目标档及之后各档的现行办理笔一并作废——退回档是"办砸了
 *    要重办"的那档，其后各档都是在办砸的基础上接着办的，同样站不住；作废笔不进
 *    单面（不许跟重办新笔并排挂），但留库可倒查。单据置"退回重办"停在退回档，
 *    必须先在退回档重办（补回该档经办记录）才许再推进，不许自己往下溜。
 *
 * @author fuce
 * @date 2026-09-27
 */
@Service
public class TDrainRescueOrderServiceImpl implements ITDrainRescueOrderService {

    /** 作业单状态 0在办 1已销号(终态) 2退回重办 */
    private static final int STATUS_ACTIVE = 0;
    private static final int STATUS_CLOSED = 1;
    private static final int STATUS_RETURNED = 2;

    /** 流水动作 1办理（撤回作废不另起行，打在办理笔的 void_* 上） */
    private static final int ACTION_HANDLE = 1;

    /** 办理记录列长 varchar(500) */
    private static final int DETAIL_MAX_LEN = 500;

    @javax.annotation.Resource
    private TDrainRescueOrderMapper drainRescueOrderMapper;

    @javax.annotation.Resource
    private TDrainRescueStepMapper drainRescueStepMapper;

    @Override
    public TDrainRescueOrder selectTDrainRescueOrderById(Long id) {
        if (id == null) {
            return null;
        }
        TDrainRescueOrder r = this.drainRescueOrderMapper.selectById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() == 1)) {
            return null;
        }
        return r;
    }

    @Override
    public List<TDrainRescueOrder> selectTDrainRescueOrderList(QueryWrapper<TDrainRescueOrder> queryWrapper) {
        QueryWrapper<TDrainRescueOrder> wrapper = queryWrapper == null
                ? new QueryWrapper<TDrainRescueOrder>() : queryWrapper;
        // 台账只摆未删单
        wrapper.eq("del_flag", 0);
        return this.drainRescueOrderMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainRescueOrder open(String siteNo, String operator, String detail) {
        String op = requireOperator(operator);
        String det = requireDetail(detail, "接单记录");
        if (siteNo == null || siteNo.trim().isEmpty()) {
            throw new DrainRescueException("抢险所压泵站编号为空，不许开单");
        }

        TDrainRescueOrder order = new TDrainRescueOrder();
        order.setSiteNo(siteNo.trim());
        order.setStage(RescueStage.ACCEPT.getCode());
        order.setStatus(STATUS_ACTIVE);
        order.setDelFlag(0);
        this.drainRescueOrderMapper.insert(order);
        order.setBizNo("QX" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + String.format("%04d", order.getId() % 10000));
        this.drainRescueOrderMapper.updateById(order);

        // 开单即接单：档0这笔当场落账，谁接的、什么时刻、接了什么活
        appendStep(order, RescueStage.ACCEPT.getCode(), 1, op, det, new Date());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainRescueOrder advance(Long id, String operator, String detail) {
        TDrainRescueOrder r = lock(id);
        String op = requireOperator(operator);
        String det = requireDetail(detail, "办理记录");
        rejectClosed(r, "推进");
        if (r.getStatus() != null && r.getStatus() == STATUS_RETURNED) {
            // 退回档还空着（旧笔已作废）：先重办补回该档记录，才许再推进，不许往下溜
            throw new DrainRescueException("单据退回重办中：请先在[" + stageName(r.getStage())
                    + "]档重办，补回该档经办记录后才许推进");
        }
        int st = r.getStage() == null ? RescueStage.FIRST_STAGE : r.getStage();
        if (st >= RescueStage.LAST_STAGE) {
            // 已在末档却不是销号态：异常数据，不再往前推，交人工核对
            throw new DrainRescueException("单据已在办结销号档却未销号，数据异常，交人工核对，不再推进");
        }

        // 只进一档，不跨档
        int next = st + 1;
        if (this.drainRescueStepMapper.getLive(r.getId(), next) != null) {
            // 目标档已有现行办理笔：没撤回过就要再挪，是同一档挪第二回
            throw new DrainRescueException("[" + stageName(next) + "]档已有现行办理记录，"
                    + "同一档不许挪第二回；办砸了请先撤回该档再重办");
        }

        // 每挪一档：经办人+时刻+办了什么落进流水；首办1，重办过则回数+1
        int attempt = this.drainRescueStepMapper.maxAttempt(r.getId(), next) + 1;
        appendStep(r, next, attempt, op, det, new Date());

        r.setStage(next);
        // 走到末档才办结销号，中间任何一档都是在办
        r.setStatus(next >= RescueStage.LAST_STAGE ? STATUS_CLOSED : STATUS_ACTIVE);
        this.drainRescueOrderMapper.updateById(r);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainRescueOrder rollback(Long id, String operator, String reason) {
        TDrainRescueOrder r = lock(id);
        String op = requireOperator(operator);
        String why = requireDetail(reason, "撤回缘由");
        rejectClosed(r, "撤回");
        if (r.getStatus() != null && r.getStatus() == STATUS_RETURNED) {
            // 已停在退回档：该档旧笔已作废，无可再退；先重办，办砸了新笔再撤
            throw new DrainRescueException("单据已退回[" + stageName(r.getStage())
                    + "]档待重办，请先重办；不许连着往下退");
        }
        int st = r.getStage() == null ? RescueStage.FIRST_STAGE : r.getStage();
        if (st <= RescueStage.FIRST_STAGE) {
            throw new DrainRescueException("单据还在接单档，前面没有档可退");
        }

        // 只回一档。退回档(办砸的那档)及之后各档的现行办理笔一并作废：
        // 作废笔不进单面、不与重办新笔并排挂单，但留库，谁撤的/何时撤的/为什么撤都可倒查
        int back = st - 1;
        this.drainRescueStepMapper.voidFromStage(r.getId(), back, op, new Date(), why);

        r.setStage(back);
        r.setStatus(STATUS_RETURNED);
        this.drainRescueOrderMapper.updateById(r);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainRescueOrder redo(Long id, String operator, String detail) {
        TDrainRescueOrder r = lock(id);
        String op = requireOperator(operator);
        String det = requireDetail(detail, "重办记录");
        rejectClosed(r, "重办");
        if (r.getStatus() == null || r.getStatus() != STATUS_RETURNED) {
            throw new DrainRescueException("只有退回重办中的单据才能重办；在办单请直接推进");
        }
        int st = r.getStage() == null ? RescueStage.FIRST_STAGE : r.getStage();
        if (this.drainRescueStepMapper.getLive(r.getId(), st) != null) {
            // 退回档竟还有现行笔：撤回时没作废干净，异常数据，不许再叠一笔
            throw new DrainRescueException("[" + stageName(st) + "]档存在未作废的办理记录，"
                    + "数据异常，交人工核对，不许重办叠笔");
        }

        // 重办落新笔：回数+1，与作废旧笔同档不同回，单面上只见新笔
        int attempt = this.drainRescueStepMapper.maxAttempt(r.getId(), st) + 1;
        appendStep(r, st, attempt, op, det, new Date());

        r.setStatus(STATUS_ACTIVE);
        this.drainRescueOrderMapper.updateById(r);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainRescueOrder supplement(Long id, String operator, String detail) {
        TDrainRescueOrder mother = lock(id);
        String op = requireOperator(operator);
        String det = requireDetail(detail, "补单缘由");
        if (mother.getStatus() == null || mother.getStatus() != STATUS_CLOSED) {
            // 在办/退回单有错漏走撤回重办；补单只补给销号单
            throw new DrainRescueException("只有已销号的单才能开补单；在办单有错漏请撤回重办");
        }

        // 母单一笔不动：新单挂 supplement_of 从接单重走五档
        TDrainRescueOrder sup = new TDrainRescueOrder();
        sup.setSiteNo(mother.getSiteNo());
        sup.setStage(RescueStage.ACCEPT.getCode());
        sup.setStatus(STATUS_ACTIVE);
        sup.setSupplementOf(mother.getId());
        sup.setDelFlag(0);
        sup.setRemark("母单 " + mother.getBizNo() + " 的补单");
        this.drainRescueOrderMapper.insert(sup);
        sup.setBizNo("QB" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + String.format("%04d", sup.getId() % 10000));
        this.drainRescueOrderMapper.updateById(sup);

        appendStep(sup, RescueStage.ACCEPT.getCode(), 1, op, det, new Date());
        return sup;
    }

    @Override
    public List<TDrainRescueStep> liveTrail(Long orderId) {
        // 单面只摆现行有效笔：作废笔绝不跟重办新笔并排挂上单面
        return this.drainRescueStepMapper.listLive(orderId);
    }

    @Override
    public List<TDrainRescueStep> trail(Long orderId) {
        // seq_no 倒序：最近的一笔在最前；作废笔一并带出，一级级倒着盘
        return this.drainRescueStepMapper.listTrail(orderId);
    }

    /** 事务内锁单：不存在或已删除一律视为无单。 */
    private TDrainRescueOrder lock(Long id) {
        if (id == null) {
            throw new DrainRescueException("抢险作业单不存在");
        }
        TDrainRescueOrder r = this.drainRescueOrderMapper.selectByIdForUpdate(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() == 1)) {
            throw new DrainRescueException("抢险作业单不存在");
        }
        return r;
    }

    /** 销号即终态：任何流转动作一律挡，指去开补单。 */
    private void rejectClosed(TDrainRescueOrder r, String action) {
        if (r.getStatus() != null && r.getStatus() == STATUS_CLOSED) {
            throw new DrainRescueException("单据已办结销号，终态关死：不许再" + action
                    + "，确有错漏请另开补单，不在本单上动");
        }
    }

    /** 落一笔办理流水：经办人+时刻+内容；seq_no 单据全局递增，倒查排序用。 */
    private void appendStep(TDrainRescueOrder order, int stage, int attempt,
                            String operator, String detail, Date at) {
        Integer total = this.drainRescueStepMapper.countByOrder(order.getId());
        TDrainRescueStep step = new TDrainRescueStep();
        step.setOrderId(order.getId());
        step.setBizNo(order.getBizNo());
        step.setStage(stage);
        step.setAttemptNo(attempt);
        step.setOperator(operator);
        step.setAction(ACTION_HANDLE);
        step.setDetail(detail);
        step.setOpTime(at);
        step.setSeqNo((total == null ? 0 : total) + 1);
        step.setVoidFlag(0);
        this.drainRescueStepMapper.insert(step);
    }

    private String requireOperator(String operator) {
        if (operator == null || operator.trim().isEmpty()) {
            throw new DrainRescueException("经办人为空：每挪一档都得写清谁办的");
        }
        return operator.trim();
    }

    private String requireDetail(String detail, String what) {
        if (detail == null || detail.trim().isEmpty()) {
            throw new DrainRescueException(what + "为空：办了什么、为什么，得写进单里");
        }
        String det = detail.trim();
        if (det.length() > DETAIL_MAX_LEN) {
            throw new DrainRescueException(what + "超长（最多" + DETAIL_MAX_LEN + "字）");
        }
        return det;
    }

    private String stageName(Integer stage) {
        try {
            return RescueStage.of(stage).getName();
        } catch (IllegalArgumentException ex) {
            return "未知档(" + stage + ")";
        }
    }
}
