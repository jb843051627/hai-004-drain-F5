package com.fc.v2.mapper.auto;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TDrainRescueOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 抢险作业单数据层
 *
 * @author fuce
 * @date 2026-09-27
 */
public interface TDrainRescueOrderMapper extends BaseMapper<TDrainRescueOrder> {

    /**
     * 行级加锁回查作业单：推进/撤回/重办/开补单必须在事务内先锁单再动状态，
     * 堵住并发下的跨档抢挪与撤回后抢推。
     */
    @Select("SELECT id, biz_no, site_no, stage, status, supplement_of, del_flag, "
            + "create_by, create_time, update_by, update_time, remark "
            + "FROM t_drain_rescue_order WHERE id = #{id} FOR UPDATE")
    TDrainRescueOrder selectByIdForUpdate(@Param("id") Long id);
}
