package com.fc.v2.mapper.auto;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TDrainWorkOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 排涝抢险作业单数据层
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface TDrainWorkOrderMapper extends BaseMapper<TDrainWorkOrder> {

    /**
     * 行级加锁回查作业单：推进/退回/改记必须在事务内先锁单再动状态，
     * 堵住并发下的跳档推进与退回后抢推。
     */
    @Select("SELECT id, biz_no, site_no, stage, status, content, last_action, del_flag, "
            + "create_by, create_time, update_by, update_time, remark "
            + "FROM t_drain_work_order WHERE id = #{id} FOR UPDATE")
    TDrainWorkOrder selectByIdForUpdate(@Param("id") Long id);

    /**
     * 该泵站是否压着没销的修单：在办(0)或退回重办(2)都算没销，
     * 压着修单的站这一轮巡检自动往后推；办结确认(1)算已销，不挡派单。
     */
    @Select("SELECT COUNT(1) FROM t_drain_work_order "
            + "WHERE site_no = #{siteNo} AND status IN (0, 2) AND del_flag = 0")
    int countOpenBySite(@Param("siteNo") String siteNo);
}
