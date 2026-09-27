package com.fc.v2.mapper.auto;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TDrainDispatchSign;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 泵站强排调度签批流水数据层
 * 计数/留痕一律走本表实笔，不拿单据上的冗余字段凑数。
 *
 * @author fuce
 * @date 2026-09-24
 */
public interface TDrainDispatchSignMapper extends BaseMapper<TDrainDispatchSign> {

    /** 本轮本节点的全部流水（批准/驳回都取，名单与去重由业务层按指定名单口径过滤）。 */
    @Select("SELECT id, bill_id, bill_no, round_no, node_no, sign_mode, approver, action, comment, "
            + "sign_time, seq_no, create_by, create_time, update_by, update_time, remark "
            + "FROM t_drain_dispatch_sign "
            + "WHERE bill_id = #{billId} AND round_no = #{roundNo} AND node_no = #{nodeNo} "
            + "ORDER BY sign_time, id")
    List<TDrainDispatchSign> listRoundNode(@Param("billId") Long billId,
                                           @Param("roundNo") int roundNo,
                                           @Param("nodeNo") int nodeNo);

    /** 单据全部流水笔数（跨轮次），用于 seq_no 全局递增。 */
    @Select("SELECT COUNT(*) FROM t_drain_dispatch_sign WHERE bill_id = #{billId}")
    int countByBill(@Param("billId") Long billId);

    /** 台账倒查：seq_no 倒序，跨轮次全保留（驳回轮次的旧痕不抹）。 */
    @Select("SELECT id, bill_id, bill_no, round_no, node_no, sign_mode, approver, action, comment, "
            + "sign_time, seq_no, create_by, create_time, update_by, update_time, remark "
            + "FROM t_drain_dispatch_sign WHERE bill_id = #{billId} "
            + "ORDER BY seq_no DESC, sign_time DESC")
    List<TDrainDispatchSign> listTrail(@Param("billId") Long billId);
}
