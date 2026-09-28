package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TDrainLevelStd;
import com.fc.v2.model.custom.LevelJudgeResult;

/**
 * 泵站起排判线标准 Service接口（rule-eval 形状：规则求值，无增删改）
 *
 * 判线是起不起排的唯一正式依据，这里只出判定，不开新增/编辑/删除：
 * 1. 有效期一律半开区间 [effStart, effEnd)：交接那一瞬间，老版已失效、新版刚生效，
 *    当下有效的只能有一版；回判历史时刻以那一刻落在区间里的那一版为准；
 * 2. 那一刻哪版都不沾，按「当下无可用判线」回话，不许拿作废老线填；
 * 3. 同刻几版叠着，照 priority(大的优先) 取优；单代号判定与全量取优走同一把尺子，
 *    两边取的必须是同一版；
 * 4. 早年只录到前两档的存量线照旧能用：缺档跳过，水位高出已划最高档归超档。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITDrainLevelStdService {

    /** 按主键查询规则 */
    TDrainLevelStd selectTDrainLevelStdById(Long id);

    /**
     * 单规则求值：按 ruleCode 在 at 时刻生效的版本判档（1..4）。
     * 无生效版本 / 输入越界 / 参数缺失一律返回 0。
     */
    int evaluate(String ruleCode, BigDecimal input, Date at);

    /** 多规则求值：at 时刻可用规则中优先级最高者的档位；无可用规则返回 0 */
    int evaluateTop(BigDecimal input, Date at);

    /** at 时刻可用规则（优先级降序、ruleCode 降序） */
    List<TDrainLevelStd> listAvailable(Date at);

    /** 单条规则在 at 时刻是否可用（批量导入/报表等旁路复用，口径须与定位/列表一致） */
    boolean usable(Long id, Date at);

    /** at 时刻可用规则条数（列表页角标 / 看板） */
    int countAvailable(Date at);

    /**
     * 单规则判定（重载，富回话）：at 照文本 yyyy-MM-dd HH:mm:ss 传，回判历史时刻直接照抄那一刻。
     * 收下但无版本 / 输入不收 / 命中版本各有明确回话，档由服务层写死，调用方不许代填。
     */
    LevelJudgeResult evaluate(String ruleCode, BigDecimal input, String atText);

    /**
     * 取优判定（重载，富回话）：at 时刻所有有效判线中取优先级最高的一版判档。
     * 与 {@link #listAvailable(Date)} 排序第一的必须是同一版。
     */
    LevelJudgeResult evaluateTop(BigDecimal input, String atText);
}
