package com.fc.v2.mapper.auto;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TDrainDispatchBill;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 泵站强排调度签批单数据层
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface TDrainDispatchBillMapper extends BaseMapper<TDrainDispatchBill> {

    /**
     * 行级加锁回查单据：签批/重报必须在事务内先锁单，再读流水重算计数，
     * 堵住并发下的抢跑与重复签。
     */
    @Select("SELECT id, bill_no, round_no, node_no, sign_mode, need_count, sign_count, "
            + "pump_scale, start_time, status, del_flag, create_by, create_time, "
            + "update_by, update_time, remark "
            + "FROM t_drain_dispatch_bill WHERE id = #{id} FOR UPDATE")
    TDrainDispatchBill selectByIdForUpdate(@Param("id") Long id);
}
