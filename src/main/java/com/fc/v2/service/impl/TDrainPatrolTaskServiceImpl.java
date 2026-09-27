package com.fc.v2.service.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TDrainDutyShiftMapper;
import com.fc.v2.mapper.auto.TDrainPatrolTaskMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.mapper.auto.TDrainWorkOrderMapper;
import com.fc.v2.model.auto.TDrainDutyShift;
import com.fc.v2.model.auto.TDrainPatrolTask;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.model.custom.PatrolRoundResult;
import com.fc.v2.model.custom.PatrolRoundResult.BadRow;
import com.fc.v2.model.custom.PatrolRoundResult.DeferredItem;
import com.fc.v2.model.custom.PatrolRoundResult.DispatchedItem;
import com.fc.v2.service.ITDrainPatrolTaskService;

/**
 * 汛期值守巡检任务条目 Service业务层处理（scheduling-job 形状：周期执行，无增删改查入口）
 *
 * 系统照值守表自己派，口径：
 * 1. 只在值守时段内派：执行时刻被有效排班半开区间[start,end)覆盖才动手；排班空档一条不派、
 *    不吭声（连"无法派单"都不记），条目原样留着等下一轮；
 * 2. 派单对象照排班：到点(status=0待派、due_at已到含恰好到期)的条目择出来交给当班值守员；
 * 3. 一人一时段最多接一座站：该班值守员在本时段(shift_id)已接过站，其余条目本轮不派；
 *    一座站同一时段不许两人轮着跟：本站本时段已有主也不再派（重排异常数据的第二道闸）；
 * 4. 站里压着没销的修单（在办/退回重办），这一轮自动往后推——条目保持待派，修单销了下轮自然择上；
 * 5. 派掉的当场挂记号（status=1已派、assignee、dispatched_at、shift_id），往后任何一轮都不再
 *    把它挑进候选；
 * 6. 某行数据磕坏（泵站编号缺失、档案不存在/停用等）只跳过这一行并留账交人工核，
 *    绝不为一行把整轮撂下；某轮一支都派不出是常事，正常回空账，不当异常。
 *
 * 不做整轮大事务：各条派定各自落库，一条炸了不回滚同轮已派的好条。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TDrainPatrolTaskServiceImpl implements ITDrainPatrolTaskService {

    /** 条目状态 1已派（0待派 仅在 SQL 口径里出现） */
    private static final int STATUS_DONE = 1;

    /** 泵站档案 0在用 1已停用 */
    private static final int SITE_IN_USE = 0;

    @javax.annotation.Resource
    private TDrainPatrolTaskMapper drainPatrolTaskMapper;

    @javax.annotation.Resource
    private TDrainDutyShiftMapper drainDutyShiftMapper;

    @javax.annotation.Resource
    private TDrainStationMapper drainStationMapper;

    @javax.annotation.Resource
    private TDrainWorkOrderMapper drainWorkOrderMapper;

    @Override
    public TDrainPatrolTask selectTDrainPatrolTaskById(Long id) {
        return this.drainPatrolTaskMapper.selectById(id);
    }

    @Override
    public List<TDrainPatrolTask> selectTDrainPatrolTaskList(QueryWrapper<TDrainPatrolTask> queryWrapper) {
        QueryWrapper<TDrainPatrolTask> wrapper = queryWrapper == null
                ? new QueryWrapper<TDrainPatrolTask>() : queryWrapper;
        // 台账只摆未删条目，逻辑删除的不进台账
        wrapper.eq("del_flag", 0);
        return this.drainPatrolTaskMapper.selectList(wrapper);
    }

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：JDBC 的 serverTimezone 与本机
     * 时区不对称，直接把 java.util.Date 作参数会整体偏移，使"恰好到期"这类边界用例错判。
     */
    private static String ts(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public List<TDrainPatrolTask> listDue(Date at) {
        Date moment = at == null ? new Date() : at;
        return this.drainPatrolTaskMapper.listWaitingDue(ts(moment));
    }

    @Override
    public int runOnce(Date at) {
        return dispatchRound(at).getDispatchedCount();
    }

    @Override
    public int runOnce() {
        return dispatchRound(new Date()).getDispatchedCount();
    }

    @Override
    public PatrolRoundResult dispatchRound() {
        return dispatchRound(new Date());
    }

    @Override
    public PatrolRoundResult dispatchRound(Date at) {
        Date moment = at == null ? new Date() : at;
        String now = ts(moment);

        // 1. 先认值守时段：没有任何有效排班覆盖此刻，就是排班空档，不派不吭声
        List<TDrainDutyShift> covers = this.drainDutyShiftMapper.listCovering(now);
        if (covers == null || covers.isEmpty()) {
            return PatrolRoundResult.idleRound(moment);
        }
        // 同一时刻撞重排属于配置异常：取起时最早、id最小的一条锚定，口径确定不抽签
        TDrainDutyShift shift = covers.get(0);

        PatrolRoundResult result = new PatrolRoundResult();
        result.setAt(moment);
        result.setDutyPeriod(true);
        result.setShiftId(shift.getId());
        result.setShiftName(shift.getShiftName());
        result.setDutyUser(shift.getDutyUser());

        // 2. 到点待派条目（SQL 已按到期时刻、id 排序），逐条着过
        List<TDrainPatrolTask> due = this.drainPatrolTaskMapper.listWaitingDue(now);
        result.setCandidateCount(due == null ? 0 : due.size());
        if (due == null || due.isEmpty()) {
            return result;
        }

        // 本轮内存里先记一份，同轮连续派多条时不重复打库
        Set<String> busyUsers = new HashSet<>();
        Set<String> takenSites = new HashSet<>();

        for (TDrainPatrolTask task : due) {
            try {
                processOne(task, shift, moment, busyUsers, takenSites, result);
            } catch (Exception ex) {
                // 哪一行磕坏跳过它回头让人核，别为一行把整轮撂下
                Long badId = task == null ? null : task.getId();
                String badItem = task == null ? null : task.getItemNo();
                String badSite = task == null ? null : task.getSiteNo();
                result.addBadRow(new BadRow(badId, badItem, badSite,
                        "处理异常已跳过：" + ex.getMessage()));
            }
        }
        return result;
    }

    /** 逐条判定：坏行→留账；压修单/人或站已占→后推；其余派定落记号。 */
    private void processOne(TDrainPatrolTask task, TDrainDutyShift shift, Date moment,
                            Set<String> busyUsers, Set<String> takenSites, PatrolRoundResult result) {
        if (task == null || task.getId() == null) {
            result.addBadRow(new BadRow(null, null, null, "空条目或缺主键"));
            return;
        }
        String siteNo = task.getSiteNo() == null ? "" : task.getSiteNo().trim();
        if (siteNo.isEmpty()) {
            // 不知道到点该巡哪座站，派不出去也不能瞎派：坏行留账交人工核
            result.addBadRow(new BadRow(task.getId(), task.getItemNo(), task.getSiteNo(),
                    "泵站编号缺失，无法派单"));
            return;
        }
        if (task.getAmount() != null && task.getAmount().signum() < 0) {
            result.addBadRow(new BadRow(task.getId(), task.getItemNo(), siteNo,
                    "应巡点位数为负，数据磕坏"));
            return;
        }
        TDrainStation site = this.drainStationMapper.selectOne(new QueryWrapper<TDrainStation>()
                .eq("site_no", siteNo).eq("del_flag", 0));
        if (site == null) {
            result.addBadRow(new BadRow(task.getId(), task.getItemNo(), siteNo,
                    "泵站档案不存在，无法派单"));
            return;
        }
        if (site.getStatus() != null && site.getStatus() != SITE_IN_USE) {
            result.addBadRow(new BadRow(task.getId(), task.getItemNo(), siteNo,
                    "泵站档案已停用，无法派单"));
            return;
        }

        // 站里压着没销的修单（在办/退回重办）：本轮自动往后推，条目保持待派，下轮再择
        if (this.drainWorkOrderMapper.countOpenBySite(siteNo) > 0) {
            result.addDeferred(new DeferredItem(task.getId(), task.getItemNo(), siteNo,
                    "站里压着未销修单，本轮自动后推"));
            return;
        }

        String dutyUser = shift.getDutyUser();
        Long shiftId = shift.getId();
        // 一人一时段最多一座站
        boolean userBusy = busyUsers.contains(dutyUser)
                || this.drainPatrolTaskMapper.countUserBusy(shiftId, dutyUser) > 0;
        if (userBusy) {
            busyUsers.add(dutyUser);
            result.addDeferred(new DeferredItem(task.getId(), task.getItemNo(), siteNo,
                    "当班值守员本时段已接一座站，本轮不派"));
            return;
        }
        // 一座站同一时段不许两人轮着跟
        boolean siteTaken = takenSites.contains(siteNo)
                || this.drainPatrolTaskMapper.countSiteTaken(shiftId, siteNo) > 0;
        if (siteTaken) {
            takenSites.add(siteNo);
            result.addDeferred(new DeferredItem(task.getId(), task.getItemNo(), siteNo,
                    "本站本时段已有值守员跟，不许两人轮跟"));
            return;
        }

        // 派定：原子 CAS 落记号，只有仍是待派的条目才更新成已派；
        // 一人一时段一站、一站一时段一人再由库唯一键 uk_shift_assignee/uk_shift_site 兜底。
        // status=1 后，往后任何一轮 listWaitingDue 都不会再挑上它。
        int rows;
        try {
            rows = this.drainPatrolTaskMapper.markDispatched(
                    task.getId(), dutyUser, ts(moment), shiftId);
        } catch (DuplicateKeyException dup) {
            // 与并发轮次撞上：本时段该值守员已接站或本站已有人跟，这条本轮让出，不动对方记号
            result.addDeferred(new DeferredItem(task.getId(), task.getItemNo(), siteNo,
                    "并发派单冲突：本时段值守员或本站已被占用，本轮让出"));
            return;
        }
        if (rows == 0) {
            // 待派条目在本轮回看期间已被并发轮次派定：不认自己派成，原样跳过
            result.addDeferred(new DeferredItem(task.getId(), task.getItemNo(), siteNo,
                    "条目已被并发轮次派定，本轮跳过"));
            return;
        }

        task.setStatus(STATUS_DONE);
        task.setAssignee(dutyUser);
        task.setDispatchedAt(moment);
        task.setShiftId(shiftId);
        busyUsers.add(dutyUser);
        takenSites.add(siteNo);
        result.addDispatched(new DispatchedItem(task.getId(), task.getItemNo(), siteNo, dutyUser));
    }
}
