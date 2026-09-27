package com.fc.v2.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TDrainDispatchBill;
import com.fc.v2.model.auto.TDrainDispatchSign;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 泵站强排调度签批单 Service接口（approval-chain 形状：三节点顺序签批）
 *
 * 红线：
 * 1.值班长 -&gt; 排水科 -&gt; 分管领导 顺序签，前节点不过后节点不许抢跑；
 * 2.会签节点指定名单全员落名才过节点，计数一律以流水表实笔重算，差一笔都不算过；
 * 3.任一指定签批人驳回，单据到此为止，后面不许再签；
 * 4.批准强排后单走到底：规模与开工时刻冻结，无任何改动入口；
 * 5.驳回重报进下一轮，上一轮批过的数按轮次清零，不携旧数，旧痕保留可倒查。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITDrainDispatchBillService {

    /** 按主键回查单据（signCount 以流水实时重算回填，页面数与库内实笔一致） */
    TDrainDispatchBill selectTDrainDispatchBillById(Long id);

    /** 台账列表查询 */
    List<TDrainDispatchBill> selectTDrainDispatchBillList(QueryWrapper<TDrainDispatchBill> queryWrapper);

    /**
     * 签批台账倒查：哪个节点谁签的、什么时刻、批了哪一句，按时序倒序返回。
     * 含被驳回轮次的旧痕，重报后上一轮记录仍在。
     */
    List<TDrainDispatchSign> trail(Long billId);

    /** 申报强排：建单进入节点0（值班长），轮次1，登记规模与开工时刻 */
    TDrainDispatchBill submit(TDrainDispatchBill form);

    /** 当前节点指定签批人签同意；或签1人过节点，会签全员才过，末节点过即批准强排 */
    TDrainDispatchBill approve(Long id, String approver, String comment);

    /** 当前节点指定签批人驳回：单据即刻终止，后面任何人不许再签 */
    TDrainDispatchBill reject(Long id, String approver, String comment);

    /** 被驳回后重报：进下一轮从上节点0重走，旧批数清零，可重新填报规模与开工时刻 */
    TDrainDispatchBill resubmit(Long id, BigDecimal pumpScale, Date startTime, String comment);
}
