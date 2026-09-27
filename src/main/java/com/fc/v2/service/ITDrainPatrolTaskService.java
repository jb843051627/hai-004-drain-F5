package com.fc.v2.service;

import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TDrainPatrolTask;
import com.fc.v2.model.custom.PatrolRoundResult;

/**
 * 汛期值守巡检任务条目 Service接口（scheduling-job 形状：周期执行，无增删改查入口）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITDrainPatrolTaskService {

    /** 按主键回查条目 */
    TDrainPatrolTask selectTDrainPatrolTaskById(Long id);

    /** 台账列表（只读，条件由页面传入，服务层统一补未删口径） */
    List<TDrainPatrolTask> selectTDrainPatrolTaskList(QueryWrapper<TDrainPatrolTask> queryWrapper);

    /** 该时刻可处理的条目（到期 + 待派） */
    List<TDrainPatrolTask> listDue(Date at);

    /** 执行一次，返回**成功派成条数**；单条失败跳过继续 */
    int runOnce(Date at);

    /** 重载：以当前时刻执行一轮（定时任务入口用），返回派成条数 */
    int runOnce();

    /**
     * 重载：按指定时刻跑一轮派单，回整轮实账。
     * 值守时段内把到点该巡的站交给当班人；排班空档不派不吭声；
     * 一人一时段最多一站、一站不两人轮跟；压着没销修单的站本轮后推；
     * 派掉的挂记号，往后各轮不再进候选；坏行跳过留账，不撂下整轮。
     */
    PatrolRoundResult dispatchRound(Date at);

    /** 重载：以当前时刻跑一轮（定时任务入口用） */
    PatrolRoundResult dispatchRound();
}
