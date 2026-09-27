package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.exception.DrainDispatchException;
import com.fc.v2.mapper.auto.TDrainDispatchBillMapper;
import com.fc.v2.mapper.auto.TDrainDispatchSignMapper;
import com.fc.v2.model.auto.TDrainDispatchBill;
import com.fc.v2.model.auto.TDrainDispatchSign;
import com.fc.v2.model.custom.DispatchNode;
import com.fc.v2.service.ITDrainDispatchBillService;

/**
 * 泵站强排调度签批单 Service业务层处理（approval-chain 形状：三节点顺序签批）
 *
 * 计数口径：本节点已签数永远拿 t_drain_dispatch_sign 实笔重算（且只计本节点指定
 * 名单内的人），单据 sign_count 只作落库镜像，绝不靠内存自增凑数——页面看到的数
 * 与库内存的数同出一源，差一笔都不算过节点。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TDrainDispatchBillServiceImpl implements ITDrainDispatchBillService {

    /** 签批单状态 0审批中 1批准强排 2已驳回(本轮终止) */
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    /** 流水动作 1批准 2驳回 */
    private static final int ACTION_APPROVE = 1;
    private static final int ACTION_REJECT = 2;

    /** 末节点（分管领导），过此节点即批准强排、流程关死 */
    private static final int LAST_NODE = 2;

    @javax.annotation.Resource
    private TDrainDispatchBillMapper drainDispatchBillMapper;

    @javax.annotation.Resource
    private TDrainDispatchSignMapper drainDispatchSignMapper;

    @Override
    public TDrainDispatchBill selectTDrainDispatchBillById(Long id) {
        TDrainDispatchBill bill = this.drainDispatchBillMapper.selectById(id);
        if (bill != null) {
            bill.setSignCount(recountNode(bill));
        }
        return bill;
    }

    @Override
    public List<TDrainDispatchBill> selectTDrainDispatchBillList(QueryWrapper<TDrainDispatchBill> queryWrapper) {
        List<TDrainDispatchBill> list = this.drainDispatchBillMapper.selectList(queryWrapper);
        // 台账页的已签数同样以实笔回填，保证页面数与库存数一致
        for (TDrainDispatchBill bill : list) {
            bill.setSignCount(recountNode(bill));
        }
        return list;
    }

    @Override
    public List<TDrainDispatchSign> trail(Long billId) {
        // seq_no 倒序：最近的一笔在最前，倒着能查；跨轮次全保留，驳回旧痕不抹
        return this.drainDispatchSignMapper.listTrail(billId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainDispatchBill submit(TDrainDispatchBill form) {
        if (form == null) {
            throw new DrainDispatchException("申报内容为空");
        }
        validateScaleAndTime(form.getPumpScale(), form.getStartTime());

        TDrainDispatchBill bill = new TDrainDispatchBill();
        bill.setRoundNo(1);
        bill.setNodeNo(DispatchNode.DUTY_LEAD.getNodeNo());
        bill.setSignMode(DispatchNode.DUTY_LEAD.getSignMode());
        bill.setNeedCount(DispatchNode.DUTY_LEAD.getNeedCount());
        bill.setSignCount(0);
        bill.setStatus(STATUS_RUNNING);
        bill.setDelFlag(0);
        bill.setPumpScale(form.getPumpScale());
        bill.setStartTime(form.getStartTime());
        bill.setRemark(form.getRemark());
        this.drainDispatchBillMapper.insert(bill);
        bill.setBillNo("QP" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + String.format("%04d", bill.getId() % 10000));
        this.drainDispatchBillMapper.updateById(bill);
        return bill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainDispatchBill approve(Long id, String approver, String comment) {
        TDrainDispatchBill bill = lockForSign(id, approver);
        DispatchNode node = DispatchNode.of(bill.getNodeNo());

        List<TDrainDispatchSign> nodeSigns = loadRoundNodeSigns(bill, node.getNodeNo());
        if (containsApprover(nodeSigns, approver)) {
            throw new DrainDispatchException(node.getNodeName() + "节点您已签过，不得重复落名");
        }

        int signedAfter = countRosterApprovals(nodeSigns, node) + 1;
        appendSign(bill, node, approver, ACTION_APPROVE, comment);

        // 或签：1笔即过节点；会签：名单全员（signedAfter==应签人数）才过，差一笔停在本节点
        boolean nodePassed = node.getSignMode() == DispatchNode.MODE_OR
                || signedAfter >= node.getNeedCount();

        if (!nodePassed) {
            bill.setSignMode(node.getSignMode());
            bill.setNeedCount(node.getNeedCount());
            bill.setSignCount(signedAfter);
            bill.setStatus(STATUS_RUNNING);
            this.drainDispatchBillMapper.updateById(bill);
            return bill;
        }

        if (node.getNodeNo() >= LAST_NODE) {
            // 末节点过：批准强排，路走到底关死。规模/开工时刻原样冻结，不在此动手。
            bill.setNodeNo(LAST_NODE);
            bill.setSignMode(node.getSignMode());
            bill.setNeedCount(node.getNeedCount());
            bill.setSignCount(signedAfter);
            bill.setStatus(STATUS_PASS);
        } else {
            DispatchNode next = DispatchNode.of(node.getNodeNo() + 1);
            bill.setNodeNo(next.getNodeNo());
            bill.setSignMode(next.getSignMode());
            bill.setNeedCount(next.getNeedCount());
            // 进入新节点，新节点一笔都还没有——不是继承，不许带着前节点的数
            bill.setSignCount(0);
            bill.setStatus(STATUS_RUNNING);
        }
        this.drainDispatchBillMapper.updateById(bill);
        return bill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainDispatchBill reject(Long id, String approver, String comment) {
        TDrainDispatchBill bill = lockForSign(id, approver);
        DispatchNode node = DispatchNode.of(bill.getNodeNo());

        List<TDrainDispatchSign> nodeSigns = loadRoundNodeSigns(bill, node.getNodeNo());
        if (containsApprover(nodeSigns, approver)) {
            throw new DrainDispatchException(node.getNodeName() + "节点您已签过，不得重复落名");
        }

        appendSign(bill, node, approver, ACTION_REJECT, comment);

        // 任一指定签批人明确驳回：本单到此为止，节点停在驳回处，后面任何人不许再签
        bill.setSignMode(node.getSignMode());
        bill.setNeedCount(node.getNeedCount());
        bill.setSignCount(countRosterApprovals(nodeSigns, node));
        bill.setStatus(STATUS_VETO);
        this.drainDispatchBillMapper.updateById(bill);
        return bill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TDrainDispatchBill resubmit(Long id, BigDecimal pumpScale, Date startTime, String comment) {
        TDrainDispatchBill bill = this.drainDispatchBillMapper.selectByIdForUpdate(id);
        if (bill == null || (bill.getDelFlag() != null && bill.getDelFlag() == 1)) {
            throw new DrainDispatchException("签批单不存在");
        }
        if (bill.getStatus() != null && bill.getStatus() == STATUS_PASS) {
            throw new DrainDispatchException("已批准强排，流程已关死，不得重报");
        }
        if (bill.getStatus() == null || bill.getStatus() != STATUS_VETO) {
            throw new DrainDispatchException("单据审批中，不得重报；被驳回后才能重报");
        }
        validateScaleAndTime(pumpScale, startTime);

        int nextRound = (bill.getRoundNo() == null ? 1 : bill.getRoundNo()) + 1;

        // 旧轮次流水原样留着倒查；计数按 round_no 过滤，上一轮批过的数不进新一轮
        bill.setRoundNo(nextRound);
        bill.setNodeNo(DispatchNode.DUTY_LEAD.getNodeNo());
        bill.setSignMode(DispatchNode.DUTY_LEAD.getSignMode());
        bill.setNeedCount(DispatchNode.DUTY_LEAD.getNeedCount());
        bill.setSignCount(0);
        bill.setStatus(STATUS_RUNNING);
        // 驳回后是新一轮申报：规模与开工时刻此刻可重新填报，批准后才冻结
        bill.setPumpScale(pumpScale);
        bill.setStartTime(startTime);
        bill.setRemark(comment);
        this.drainDispatchBillMapper.updateById(bill);
        return bill;
    }

    /** 事务内锁单并校验：存在、审批中、签批人属于当前节点指定名单。 */
    private TDrainDispatchBill lockForSign(Long id, String approver) {
        if (approver == null || approver.trim().isEmpty()) {
            throw new DrainDispatchException("签批人为空");
        }
        TDrainDispatchBill bill = this.drainDispatchBillMapper.selectByIdForUpdate(id);
        if (bill == null || (bill.getDelFlag() != null && bill.getDelFlag() == 1)) {
            throw new DrainDispatchException("签批单不存在");
        }
        if (bill.getStatus() != null && bill.getStatus() == STATUS_VETO) {
            throw new DrainDispatchException("该单已被驳回终止，后面不许再签；如需强排请驳回后重报");
        }
        if (bill.getStatus() != null && bill.getStatus() == STATUS_PASS) {
            throw new DrainDispatchException("已批准强排，此路走到底已关死，不得再签");
        }
        DispatchNode node = DispatchNode.of(bill.getNodeNo());
        if (!node.contains(approver.trim())) {
            // 前节点没批完时，后节点签批人同样落在这里：当前节点名单里没有他
            throw new DrainDispatchException("当前为[" + node.getNodeName() + "]节点，" + approver
                    + "不是该节点指定签批人，前一节点没过不许抢跑");
        }
        return bill;
    }

    /** 落一笔签批流水，seq_no 在单据全局递增，跨轮次连续可倒查。 */
    private void appendSign(TDrainDispatchBill bill, DispatchNode node, String approver,
                            int action, String comment) {
        Integer total = this.drainDispatchSignMapper.countByBill(bill.getId());
        TDrainDispatchSign sign = new TDrainDispatchSign();
        sign.setBillId(bill.getId());
        sign.setBillNo(bill.getBillNo());
        sign.setRoundNo(bill.getRoundNo() == null ? 1 : bill.getRoundNo());
        sign.setNodeNo(node.getNodeNo());
        sign.setSignMode(node.getSignMode());
        sign.setApprover(approver.trim());
        sign.setAction(action);
        sign.setComment(comment);
        sign.setSignTime(new Date());
        sign.setSeqNo((total == null ? 0 : total) + 1);
        this.drainDispatchSignMapper.insert(sign);
    }

    /** 本轮本节点的全部流水（含驳回笔，驳回由单据状态闸住）。 */
    private List<TDrainDispatchSign> loadRoundNodeSigns(TDrainDispatchBill bill, int nodeNo) {
        return this.drainDispatchSignMapper.listRoundNode(
                bill.getId(), bill.getRoundNo() == null ? 1 : bill.getRoundNo(), nodeNo);
    }

    /** 实笔重算：本轮本节点、动作批准、且签人在指定名单内的去重人数。名单外的旧笔不凑数。 */
    private int countRosterApprovals(List<TDrainDispatchSign> signs, DispatchNode node) {
        List<String> hit = new ArrayList<>();
        for (TDrainDispatchSign s : signs) {
            if (s.getAction() != null && s.getAction() == ACTION_APPROVE
                    && node.contains(s.getApprover()) && !hit.contains(s.getApprover())) {
                hit.add(s.getApprover());
            }
        }
        return hit.size();
    }

    /** 读路径计数：按单据当前轮次/当前节点重算。迁移来的半截单照实认笔即由此实现。 */
    private int recountNode(TDrainDispatchBill bill) {
        if (bill.getStatus() != null && (bill.getStatus() == STATUS_PASS
                || bill.getStatus() == STATUS_VETO)) {
            // 终态单：返回终态节点当时落库的镜像数，避免用"当前节点"口径误算
            return bill.getSignCount() == null ? 0 : bill.getSignCount();
        }
        DispatchNode node = DispatchNode.of(bill.getNodeNo() == null ? 0 : bill.getNodeNo());
        return countRosterApprovals(loadRoundNodeSigns(bill, node.getNodeNo()), node);
    }

    private boolean containsApprover(List<TDrainDispatchSign> signs, String approver) {
        for (TDrainDispatchSign s : signs) {
            if (approver.trim().equals(s.getApprover())) {
                return true;
            }
        }
        return false;
    }

    private void validateScaleAndTime(BigDecimal pumpScale, Date startTime) {
        if (pumpScale == null || pumpScale.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DrainDispatchException("强排规模必须为正数");
        }
        if (startTime == null) {
            throw new DrainDispatchException("开工时刻不能为空");
        }
    }
}
