package com.fc.v2.model.custom;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * 起排判定回话（判线只出判定，判定出来的档不让人代填）
 *
 * 三种结局，调用方照字段认：
 * 1. accepted=true, available=true ：at 时刻命中一版有效判线，level 即判定档，附该版快照；
 * 2. accepted=true, available=false：输入照收，但 at 时刻哪版都不沾（空档/全作废），
 *    level 与判线快照一律为空——按「当下无可用判线」认，不许拿作废老线填；
 * 3. accepted=false：输入本身不合法（负数、数量级离谱、参数缺失），整笔不收，不留判定。
 *
 * @author fuce
 * @date 2026-09-27
 */
@ApiModel(value = "LevelJudgeResult", description = "起排判定回话")
public class LevelJudgeResult implements Serializable {
    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "输入是否收下(false=水位不合法整笔不收，无判定)")
    private boolean accepted;

    @ApiModelProperty(value = "at 时刻是否有有效判线(false=当下无可用判线，不许拿作废老线填)")
    private boolean available;

    @ApiModelProperty(value = "判定档 1一档 2二档 3三档 4超三档；无判定时为空")
    private Integer level;

    @ApiModelProperty(value = "命中档对应的水位上限(存量两档线判到超档时为空)")
    private BigDecimal levelLimit;

    @ApiModelProperty(value = "命中判线主键")
    private Long ruleId;

    @ApiModelProperty(value = "命中判线代号")
    private String ruleCode;

    @ApiModelProperty(value = "命中判线名称")
    private String ruleName;

    @ApiModelProperty(value = "取优序号")
    private Integer priority;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "命中版本生效起始时刻")
    private Date effStart;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "命中版本失效截止时刻(不含)，空=长期有效")
    private Date effEnd;

    @ApiModelProperty(value = "回话说明")
    private String message;

    /** 输入不合法：整笔不收 */
    public static LevelJudgeResult rejected(String message) {
        LevelJudgeResult r = new LevelJudgeResult();
        r.accepted = false;
        r.available = false;
        r.message = message;
        return r;
    }

    /** 输入收下了，但那一刻没有任何一版有效判线 */
    public static LevelJudgeResult noRule(String message) {
        LevelJudgeResult r = new LevelJudgeResult();
        r.accepted = true;
        r.available = false;
        r.message = message;
        return r;
    }

    /** 命中一版判线，档由服务层判定后写死 */
    public static LevelJudgeResult of(com.fc.v2.model.auto.TDrainLevelStd rule, int level) {
        LevelJudgeResult r = new LevelJudgeResult();
        r.accepted = true;
        r.available = true;
        r.level = level;
        r.ruleId = rule.getId();
        r.ruleCode = rule.getRuleCode();
        r.ruleName = rule.getRuleName();
        r.priority = rule.getPriority();
        r.effStart = rule.getEffStart();
        r.effEnd = rule.getEffEnd();
        r.levelLimit = limitOf(rule, level);
        r.message = "判定档：" + level;
        return r;
    }

    private static BigDecimal limitOf(com.fc.v2.model.auto.TDrainLevelStd rule, int level) {
        if (level == 1) {
            return rule.getTh1Max();
        }
        if (level == 2) {
            return rule.getTh2Max();
        }
        if (level == 3) {
            return rule.getTh3Max();
        }
        return null;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public boolean isAvailable() {
        return available;
    }

    public Integer getLevel() {
        return level;
    }

    public BigDecimal getLevelLimit() {
        return levelLimit;
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

    public Integer getPriority() {
        return priority;
    }

    public Date getEffStart() {
        return effStart;
    }

    public Date getEffEnd() {
        return effEnd;
    }

    public String getMessage() {
        return message;
    }
}
