package com.fc.v2.mapper.auto;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TDrainLevelStd;

/**
 * 泵站起排判线标准数据层
 *
 * 判线只按版本窗口取用：某刻被一条未删、现行、窗口齐全（eff_start/eff_end 非空）的
 * 版本半开区间 [eff_start, eff_end) 覆盖，它才是可用版本——湿季版与干季版交接那一瞬间
 * 前后，当下有效的只能有一版（end 不含，新老版在切换点上不重叠）。
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface TDrainLevelStdMapper extends BaseMapper<TDrainLevelStd> {

    /**
     * at 时刻处于有效期内的现行版本（半开区间：eff_start &lt;= at &lt; eff_end）。
     * 已作废(1)、已删除、窗口没录全的版本一律不回——回判历史时刻不许拿作废老线填空。
     * 排序照取优口径钉死：优先级降序；并列按 ruleCode 降序、id 升序，两边取的必须同一版。
     * 时间比较统一走墙钟字符串，避开 JDBC serverTimezone 与本机时区不对称造成的边界错判。
     */
    @Select("SELECT id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, "
            + "priority, status, del_flag, create_by, create_time, update_by, update_time, remark "
            + "FROM t_drain_level_std "
            + "WHERE del_flag = 0 AND status = 0 "
            + "AND eff_start IS NOT NULL AND eff_end IS NOT NULL "
            + "AND eff_start <= #{at} AND eff_end > #{at} "
            + "ORDER BY priority DESC, rule_code DESC, id ASC")
    List<TDrainLevelStd> listEffective(@Param("at") String at);
}
