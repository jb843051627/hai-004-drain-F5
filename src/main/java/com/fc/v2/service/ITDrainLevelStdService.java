package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TDrainLevelStd;
import com.fc.v2.model.custom.JudgeResult;

/**
 * 泵站起排判线标准 Service接口（rule-eval 形状：规则求值，无增删改）
 *
 * 判线是正式依据，按版本窗口取用：回判历史时刻，以那一刻处在有效期 [eff_start, eff_end)
 * 里的现行版为准；那一刻哪版都不沾直回"当下无可用判线"，不许拿作废老线填。
 * 同刻叠版照取优序号 priority 降序（并列 ruleCode 降序、id 升序），指不指定代号两路取的
 * 必须同一版。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITDrainLevelStdService {

    /** 按主键查询规则 */
    TDrainLevelStd selectTDrainLevelStdById(Long id);

    /**
     * 起排判定（判线唯一对外结论形状）：不指定代号，在 at 时刻全部生效版本里照取优序号
     * 取头一版判档。at 为空按当下。无生效版本回 available=false"当下无可用判线"；
     * 水位为负或数量级明显不对回 badInput=true，不做判定。
     */
    JudgeResult judge(BigDecimal waterLevel, Date at);

    /**
     * 起排判定（指定判线代号）：只在该代号 at 时刻生效的版本里取头一版，取优口径与
     * 不指定代号那路完全一致；代号为空按不指定代号走。
     */
    JudgeResult judge(String ruleCode, BigDecimal waterLevel, Date at);

    /**
     * 单规则求值：按 ruleCode 在 at 时刻生效的版本判档（1..4）。
     * 无生效版本 / 输入越界 / 参数缺失一律返回 0。
     */
    int evaluate(String ruleCode, BigDecimal input, Date at);

    /** 多规则求值：at 时刻可用规则中优先级最高者的档位；无可用规则返回 0 */
    int evaluateTop(BigDecimal input, Date at);

    /** at 时刻可用规则（优先级降序、ruleCode 降序、id 升序）；at 为空按当下 */
    List<TDrainLevelStd> listAvailable(Date at);

    /** 单条规则在 at 时刻是否可用（批量导入/报表等旁路复用，口径须与定位/列表一致） */
    boolean usable(Long id, Date at);

    /** at 时刻可用规则条数（列表页角标 / 看板） */
    int countAvailable(Date at);
}
