package com.fc.v2.model.custom;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fc.v2.model.auto.TDrainLevelStd;

/**
 * 起排判定回话（判线唯一对外结论形状）。
 *
 * 判线只出判定，判定出来的档不让人代填：字段全 final、没有 setter，
 * 档位列 level 只由服务层求值后随构造落下，页面/调用方拿不到改写的口子。
 * - 正常判出：available=true，带版本身份（id/代号/名称）与档位 1..3，超全部已划线档归 4；
 * - 那一刻哪版都不沾：available=false，level=null，reason 直回"当下无可用判线"，
 *   不许拿作废老线填；
 * - 水位本身不合法（负数、数量级明显不对）：badInput=true，available=false，不做判定。
 *
 * @author fuce
 * @date 2026-09-28
 */
public final class JudgeResult {

    /** 入参水位（照实回摆，便于调用方对账） */
    private final BigDecimal waterLevel;

    /** 回判时刻 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private final Date at;

    /** 当下有没有可用判线（不合法水位也算不可判） */
    private final boolean available;

    /** 水位入参是否不合法（负数 / 数量级明显不对） */
    private final boolean badInput;

    /** 实际采用的版本主键（无判定时 null） */
    private final Long ruleId;

    /** 实际采用的判线代号（无判定时 null） */
    private final String ruleCode;

    /** 实际采用的判线名称（无判定时 null） */
    private final String ruleName;

    /** 判定档：1/2/3 已划线档，超过全部已划线档归 4；无判定为 null，不许外部代填 */
    private final Integer level;

    /** 没判出时的原由（正常判出为 null） */
    private final String reason;

    private JudgeResult(BigDecimal waterLevel, Date at, boolean available, boolean badInput,
                        TDrainLevelStd rule, Integer level, String reason) {
        this.waterLevel = waterLevel;
        this.at = at;
        this.available = available;
        this.badInput = badInput;
        this.ruleId = rule == null ? null : rule.getId();
        this.ruleCode = rule == null ? null : rule.getRuleCode();
        this.ruleName = rule == null ? null : rule.getRuleName();
        this.level = level;
        this.reason = reason;
    }

    /** 正常判出：版本身份与档位都由服务层求值后封死。 */
    public static JudgeResult judged(TDrainLevelStd rule, BigDecimal waterLevel, Date at, int level) {
        return new JudgeResult(waterLevel, at, true, false, rule, level, null);
    }

    /** 那一刻哪版都不沾：直回"当下无可用判线"，不许拿作废老线填。 */
    public static JudgeResult noRule(BigDecimal waterLevel, Date at) {
        return new JudgeResult(waterLevel, at, false, false, null, null, "当下无可用判线");
    }

    /** 水位不合法（负数/数量级明显不对）：不收，不做判定。 */
    public static JudgeResult badInput(BigDecimal waterLevel, Date at, String reason) {
        return new JudgeResult(waterLevel, at, false, true, null, null, reason);
    }

    public BigDecimal getWaterLevel() {
        return waterLevel;
    }

    public Date getAt() {
        return at;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isBadInput() {
        return badInput;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public String getRuleName() {
        return ruleName;
    }

    public Integer getLevel() {
        return level;
    }

    public String getReason() {
        return reason;
    }
}
