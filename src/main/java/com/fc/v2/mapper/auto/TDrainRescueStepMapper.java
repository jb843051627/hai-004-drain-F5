package com.fc.v2.mapper.auto;

import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TDrainRescueStep;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 抢险作业单档级流水数据层
 * 每档经办人/时刻/办理内容一律走本表实笔，主表不冗余留痕。
 *
 * @author fuce
 * @date 2026-09-27
 */
public interface TDrainRescueStepMapper extends BaseMapper<TDrainRescueStep> {

    String COLS = "id, order_id, biz_no, stage, attempt_no, operator, action, detail, op_time, "
            + "seq_no, void_flag, void_by, void_time, void_reason, "
            + "create_by, create_time, update_by, update_time, remark";

    /** 单据全部流水笔数（含作废笔），用于 seq_no 全局递增。 */
    @Select("SELECT COUNT(*) FROM t_drain_rescue_step WHERE order_id = #{orderId}")
    int countByOrder(@Param("orderId") Long orderId);

    /** 本档已办到第几回（含作废笔）：重办落新笔时 attempt_no 在此基础上 +1。 */
    @Select("SELECT IFNULL(MAX(attempt_no), 0) FROM t_drain_rescue_step "
            + "WHERE order_id = #{orderId} AND stage = #{stage}")
    int maxAttempt(@Param("orderId") Long orderId, @Param("stage") int stage);

    /** 本档现行办理笔（每档同一时刻至多一笔；查不到说明本档还没办或已撤回待重办）。 */
    @Select("SELECT " + COLS + " FROM t_drain_rescue_step "
            + "WHERE order_id = #{orderId} AND stage = #{stage} AND void_flag = 0 "
            + "ORDER BY id DESC LIMIT 1")
    TDrainRescueStep getLive(@Param("orderId") Long orderId, @Param("stage") int stage);

    /**
     * 撤回作废：把退回目标档及之后各档的现行办理笔一笔作废，
     * 谁撤的、什么时刻、为什么撤一并记上。作废笔不进单面，但留库可倒查。
     */
    @Update("UPDATE t_drain_rescue_step SET void_flag = 1, void_by = #{voidBy}, "
            + "void_time = #{voidTime}, void_reason = #{voidReason} "
            + "WHERE order_id = #{orderId} AND stage >= #{fromStage} AND void_flag = 0")
    int voidFromStage(@Param("orderId") Long orderId, @Param("fromStage") int fromStage,
                      @Param("voidBy") String voidBy, @Param("voidTime") Date voidTime,
                      @Param("voidReason") String voidReason);

    /** 单面留痕：只看现行有效笔，seq_no 正序——作废笔不许跟重办新笔并排挂上单面。 */
    @Select("SELECT " + COLS + " FROM t_drain_rescue_step "
            + "WHERE order_id = #{orderId} AND void_flag = 0 ORDER BY seq_no, id")
    List<TDrainRescueStep> listLive(@Param("orderId") Long orderId);

    /** 倒查留痕：seq_no 倒序，作废笔一并带出（谁撤的/何时撤的都在），一级级倒着盘。 */
    @Select("SELECT " + COLS + " FROM t_drain_rescue_step WHERE order_id = #{orderId} "
            + "ORDER BY seq_no DESC, id DESC")
    List<TDrainRescueStep> listTrail(@Param("orderId") Long orderId);
}
