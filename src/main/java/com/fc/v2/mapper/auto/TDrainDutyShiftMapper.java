package com.fc.v2.mapper.auto;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TDrainDutyShift;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 汛期泵站值守排班表数据层
 *
 * 系统照排班自己派：某时刻被某条有效排班半开区间 [start_at, end_at) 覆盖，
 * 该班值守员就是派单对象；没有一条覆盖即是空档。
 *
 * @author fuce
 * @date 2026-09-26
 */
public interface TDrainDutyShiftMapper extends BaseMapper<TDrainDutyShift> {

    /**
     * 覆盖指定时刻的有效排班（半开区间：start_at &lt;= at &lt; end_at），
     * 按起时、id 排序——同一时刻撞上重排的异常数据时，派单照此序先到先得，口径确定不抽签。
     * 时间比较统一走墙钟字符串，避开 JDBC serverTimezone 与本机时区不对称造成的边界错判。
     */
    @Select("SELECT id, shift_name, start_at, end_at, duty_user, status, del_flag, "
            + "create_by, create_time, update_by, update_time, remark "
            + "FROM t_drain_duty_shift "
            + "WHERE status = 0 AND del_flag = 0 "
            + "AND start_at <= #{at} AND end_at > #{at} "
            + "ORDER BY start_at, id")
    List<TDrainDutyShift> listCovering(@Param("at") String at);
}
