package com.fc.v2.mapper.auto;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TDrainMeterBatch;

/**
 * 远传计量成批汇交批次头数据层
 *
 * batch_no 即主键：同一批次号的并发热身只能有一个 insert 落进来，
 * 靠库的唯一约束兜底，重复提交在这道闸门口被挡死，不靠应用层先查后插。
 *
 * @author fuce
 * @date 2026-09-26
 */
public interface TDrainMeterBatchMapper extends BaseMapper<TDrainMeterBatch> {

    /**
     * 当前读认领头一笔：并发输家撞唯一键后用它取赢家已写实的计数。
     * 赢家事务没提交时本语句在库内等锁；赢家回滚则读到 null，交调用方退回复认。
     */
    @Select("SELECT batch_no, total_count, ok_count, fail_count, create_by, create_time "
            + "FROM t_drain_meter_batch WHERE batch_no = #{batchNo} FOR UPDATE")
    TDrainMeterBatch selectForUpdate(@Param("batchNo") String batchNo);
}
