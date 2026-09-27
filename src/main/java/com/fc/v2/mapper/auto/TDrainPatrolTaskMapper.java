package com.fc.v2.mapper.auto;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TDrainPatrolTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 汛期值守巡检任务条目数据层
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface TDrainPatrolTaskMapper extends BaseMapper<TDrainPatrolTask> {

    String COLUMNS = "id, item_no, site_no, due_at, amount, status, assignee, dispatched_at, "
            + "shift_id, del_flag, create_by, create_time, update_by, update_time, remark";

    /**
     * 到点未派的条目：status=0 待派、未删、到期时刻已到（含恰好到期），按到期时刻、id 排序——
     * 派单照到期先后落，不挑肥拣瘦。墙钟字符串比较避开时区边界坑。
     */
    @Select("SELECT " + COLUMNS + " FROM t_drain_patrol_task "
            + "WHERE del_flag = 0 AND status = 0 "
            + "AND due_at IS NOT NULL AND due_at <= #{at} "
            + "ORDER BY due_at, id")
    List<TDrainPatrolTask> listWaitingDue(@Param("at") String at);

    /**
     * 该值守时段内该值守员已接站数。一人一时段最多一座站：
     * 返回 1 即该班人已经有站，这一轮其余条目不再派给他。
     */
    @Select("SELECT COUNT(1) FROM t_drain_patrol_task "
            + "WHERE del_flag = 0 AND status = 1 AND shift_id = #{shiftId} AND assignee = #{dutyUser}")
    int countUserBusy(@Param("shiftId") Long shiftId, @Param("dutyUser") String dutyUser);

    /**
     * 该值守时段内该泵站已被人跟的笔数。一座站同一时段不许两人轮着跟：
     * 返回 1 即本站本时段已有主，后来的条目不许再换个人派。
     */
    @Select("SELECT COUNT(1) FROM t_drain_patrol_task "
            + "WHERE del_flag = 0 AND status = 1 AND shift_id = #{shiftId} AND site_no = #{siteNo}")
    int countSiteTaken(@Param("shiftId") Long shiftId, @Param("siteNo") String siteNo);

    /**
     * 原子派定（CAS）：只有仍是待派(0)的条目才落已派记号，返回1；
     * 已被并发轮次抢走（status≠0）返回0，调用方按未派成处理。
     * 一人一时段一站、一站一时段一人再由 uk_shift_assignee / uk_shift_site 两个唯一键兜底，
     * 并发撞键抛 DuplicateKeyException。派定时刻走墙钟字符串，避时区边界坑。
     */
    @Update("UPDATE t_drain_patrol_task SET status = 1, assignee = #{assignee}, "
            + "dispatched_at = #{dispatchedAt}, shift_id = #{shiftId} "
            + "WHERE id = #{id} AND status = 0 AND del_flag = 0")
    int markDispatched(@Param("id") Long id, @Param("assignee") String assignee,
                       @Param("dispatchedAt") String dispatchedAt, @Param("shiftId") Long shiftId);
}
