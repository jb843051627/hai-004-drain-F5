package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TDrainWorkOrderMapper;
import com.fc.v2.model.auto.TDrainWorkOrder;
import com.fc.v2.service.ITDrainWorkOrderService;

/**
 * 排涝抢险作业单 Service业务层处理（state-machine 形状：单据逐档流转）
 *
 * 口径（与 t_drain_work_order 列字典一致）：
 * 环节 stage 固定 0..4，只许一档一档走，每一档都有人把守——推进只 +1，不跳档；
 * 状态 status 0在办 1办结确认 2退回重办。没走到末档(4)之前一律"在办"，
 * 只有走到末档才"办结确认"，办结即关死，不许再推进/退回/改记/删除。
 * 退回只回一档并置"退回重办"停在该处，后面不许自己往下溜；重新提交(推进)
 * 才从退回档重走，且只进一档，不带上一轮的进度，绝无"第二次提交就满员"。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TDrainWorkOrderServiceImpl implements ITDrainWorkOrderService {

    /** 末环节：走到此档即办结确认（环节取值 0..4） */
    private static final int MAX_STAGE = 4;

    /** 作业单状态 0在办 1办结确认 2退回重办 */
    private static final int STATUS_ACTIVE = 0;
    private static final int STATUS_CLOSED = 1;
    private static final int STATUS_RETURNED = 2;

    /** content 列长 varchar(255)，留痕只保留最近若干笔，超长丢最旧的头部 */
    private static final int CONTENT_MAX_LEN = 240;

    @javax.annotation.Resource
    private TDrainWorkOrderMapper drainWorkOrderMapper;

    @Override
    public TDrainWorkOrder selectTDrainWorkOrderById(Long id) {
        TDrainWorkOrder r = this.drainWorkOrderMapper.selectById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() == 1)) {
            return null;
        }
        return r;
    }

    @Override
    public List<TDrainWorkOrder> selectTDrainWorkOrderList(QueryWrapper<TDrainWorkOrder> queryWrapper) {
        QueryWrapper<TDrainWorkOrder> wrapper = queryWrapper == null
                ? new QueryWrapper<TDrainWorkOrder>() : queryWrapper;
        // 台账只摆正常单，逻辑删除的不进台账
        wrapper.eq("del_flag", 0);
        return this.drainWorkOrderMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainWorkOrder advance(Long id, String remark) {
        TDrainWorkOrder r = lock(id);
        if (r == null) {
            return null;
        }
        // 办结关死：不许再推进
        if (isClosed(r)) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        // 已在末档却不是办结态属于异常数据，不再往前推，交人工核对
        if (st >= MAX_STAGE) {
            return null;
        }

        // 只进一档，不跳档；退回重办的单子重新提交也只从退回档进一档，旧进度不随行
        int next = st + 1;
        r.setStage(next);
        // 走到末档才"办结确认"，中间任何一档都是"在办"
        r.setStatus(next >= MAX_STAGE ? STATUS_CLOSED : STATUS_ACTIVE);
        r.setLastAction(remark);
        appendTrail(r, "推进至环节" + next, remark);
        this.drainWorkOrderMapper.updateById(r);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainWorkOrder rollback(Long id, String remark) {
        TDrainWorkOrder r = lock(id);
        if (r == null) {
            return null;
        }
        // 办结关死：不许退回
        if (isClosed(r)) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        // 还在起点环节，无处可退
        if (st <= 0) {
            return null;
        }

        // 只回一档，置"退回重办"停在该处——否决即停，后面不许再自己往下溜
        int back = st - 1;
        r.setStage(back);
        r.setStatus(STATUS_RETURNED);
        r.setLastAction(remark);
        appendTrail(r, "退回环节" + back + "重办", remark);
        this.drainWorkOrderMapper.updateById(r);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateContent(Long id, String remark) {
        TDrainWorkOrder r = lock(id);
        if (r == null) {
            return false;
        }
        // 已办结不得再改
        if (isClosed(r)) {
            return false;
        }
        r.setContent(remark);
        return this.drainWorkOrderMapper.updateById(r) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long id) {
        TDrainWorkOrder r = lock(id);
        if (r == null) {
            return false;
        }
        // 已办结不得删除（结案留档，倒账要翻得到）；其余做逻辑删除，不物理抹除
        if (isClosed(r)) {
            return false;
        }
        r.setDelFlag(1);
        return this.drainWorkOrderMapper.updateById(r) > 0;
    }

    /** 事务内锁单：不存在或已删除视为无单。 */
    private TDrainWorkOrder lock(Long id) {
        if (id == null) {
            return null;
        }
        TDrainWorkOrder r = this.drainWorkOrderMapper.selectByIdForUpdate(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() == 1)) {
            return null;
        }
        return r;
    }

    private boolean isClosed(TDrainWorkOrder r) {
        return r.getStatus() != null && r.getStatus() == STATUS_CLOSED;
    }

    /** 在推进记录上逐笔留痕：时刻+动作+批语，倒着翻得出来；超长只丢最旧头部。 */
    private void appendTrail(TDrainWorkOrder r, String action, String remark) {
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        String line = stamp + " " + action + (remark == null || remark.trim().isEmpty() ? "" : "：" + remark.trim());
        String prev = r.getContent();
        String merged = (prev == null || prev.isEmpty() ? line : prev + "\n" + line);
        if (merged.length() > CONTENT_MAX_LEN) {
            merged = merged.substring(merged.length() - CONTENT_MAX_LEN);
        }
        r.setContent(merged);
    }
}
