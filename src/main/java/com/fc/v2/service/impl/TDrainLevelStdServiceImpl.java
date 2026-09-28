package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TDrainLevelStdMapper;
import com.fc.v2.model.auto.TDrainLevelStd;
import com.fc.v2.model.custom.LevelJudgeResult;
import com.fc.v2.service.ITDrainLevelStdService;

/**
 * 泵站起排判线标准 Service业务层处理（rule-eval 形状）
 *
 * 判线是起不起排的唯一正式依据，判定只在这里出，不开增删改：
 * 1. 有效期一律半开区间 [effStart, effEnd)：湿季版与干季版交接的那一瞬间，老版已失效、
 *    新版刚生效，当下有效的只能有一版；回判历史时刻，以那一刻落在区间里的那一版为准；
 * 2. 那一刻哪版都不沾，直回「当下无可用判线」；已作废(status=1)的老线任何时刻都不许
 *    拿来填，哪怕回判的时刻落在它当年的有效期里；
 * 3. 同刻几版叠着，照 priority 取优（数值越大越优先）；单代号判定、全量取优、可用列表
 *    共用同一把尺子，两边取的必须是同一版；
 * 4. 早年只录到前两档的存量线照旧能用：为 NULL 的档视作未划线，分档顺次往下着，
 *    水位高出全部已划档归超档(4)；
 * 5. 水位为负、或数量级明显离谱（三位数开外）的输入不收。
 *
 * 实现说明：判线版本是小型对照表，全量读入后在 Java 侧过滤窗口/状态并排序——
 * 窗口与取优口径全系统只有这一份实现，不存在 SQL 与 Java 两处漂移；Date.compareTo
 * 走 JVM 时区，也绕开 JDBC serverTimezone 与本机时区不对称的老坑。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TDrainLevelStdServiceImpl implements ITDrainLevelStdService {

    /** 判线状态 1=已作废：任何时刻都不可用，回判历史也不许拿它填 */
    private static final int STATUS_VOID = 1;

    /** 水位 sanity 上限(米)：前池水位到不了三位数，超出即数量级离谱，整笔不收 */
    private static final BigDecimal MAX_INPUT = new BigDecimal("100");

    /** 判定时刻文本格式：回判历史时刻照抄那一刻 */
    private static final String AT_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 取优尺子：priority 大的优先；并列按 ruleCode 降序、再按 id 降序（确定性兜底）。
     * 单代号判定、全量取优、可用列表共用这一把，保证两边取的是同一版。
     */
    private static final Comparator<TDrainLevelStd> PICK_ORDER = new Comparator<TDrainLevelStd>() {
        @Override
        public int compare(TDrainLevelStd a, TDrainLevelStd b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            int byCode = cb.compareTo(ca);
            if (byCode != 0) {
                return byCode;
            }
            long ia = a.getId() == null ? 0L : a.getId();
            long ib = b.getId() == null ? 0L : b.getId();
            return Long.compare(ib, ia);
        }
    };

    @javax.annotation.Resource
    private TDrainLevelStdMapper drainLevelStdMapper;

    @Override
    public TDrainLevelStd selectTDrainLevelStdById(Long id) {
        return this.drainLevelStdMapper.selectById(id);
    }

    @Override
    public List<TDrainLevelStd> listAvailable(Date at) {
        if (at == null) {
            return new ArrayList<TDrainLevelStd>();
        }
        return availableAt(at);
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        LevelJudgeResult r = judge(ruleCode, input, at);
        return r.isAccepted() && r.isAvailable() ? r.getLevel() : 0;
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        LevelJudgeResult r = judgeTop(input, at);
        return r.isAccepted() && r.isAvailable() ? r.getLevel() : 0;
    }

    @Override
    public LevelJudgeResult evaluate(String ruleCode, BigDecimal input, String atText) {
        return judge(ruleCode, input, parseAt(atText));
    }

    @Override
    public LevelJudgeResult evaluateTop(BigDecimal input, String atText) {
        return judgeTop(input, parseAt(atText));
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TDrainLevelStd r = this.drainLevelStdMapper.selectById(id);
        return alive(r) && inWindow(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        return availableAt(at).size();
    }

    /** 单代号判定核心：at 时刻该代号下落在有效期里的那一版判档 */
    private LevelJudgeResult judge(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty()) {
            return LevelJudgeResult.rejected("判线代号不能为空");
        }
        LevelJudgeResult bad = checkInput(input, at);
        if (bad != null) {
            return bad;
        }
        String code = ruleCode.trim();
        for (TDrainLevelStd r : availableAt(at)) {
            if (code.equals(r.getRuleCode())) {
                return LevelJudgeResult.of(r, levelOf(r, input));
            }
        }
        return LevelJudgeResult.noRule("判线[" + code + "]当下无可用判线");
    }

    /** 取优判定核心：at 时刻全部有效判线里的第一版（与可用列表排序第一必须同一版） */
    private LevelJudgeResult judgeTop(BigDecimal input, Date at) {
        LevelJudgeResult bad = checkInput(input, at);
        if (bad != null) {
            return bad;
        }
        List<TDrainLevelStd> avail = availableAt(at);
        if (avail.isEmpty()) {
            return LevelJudgeResult.noRule("当下无可用判线");
        }
        TDrainLevelStd top = avail.get(0);
        return LevelJudgeResult.of(top, levelOf(top, input));
    }

    /** 输入红线：参数缺失、负数、数量级离谱一律不收 */
    private LevelJudgeResult checkInput(BigDecimal input, Date at) {
        if (input == null) {
            return LevelJudgeResult.rejected("水位数值不能为空");
        }
        if (at == null) {
            return LevelJudgeResult.rejected("判定时刻缺失或格式不对(" + AT_PATTERN + ")");
        }
        if (input.compareTo(BigDecimal.ZERO) < 0) {
            return LevelJudgeResult.rejected("水位数值不得为负，整笔不收");
        }
        if (input.compareTo(MAX_INPUT) > 0) {
            return LevelJudgeResult.rejected("水位数值数量级明显不对(超过" + MAX_INPUT.toPlainString() + ")，整笔不收");
        }
        return null;
    }

    /** at 时刻可用的全部判线：未删、未作废、有效期半开区间覆盖 at，按取优尺子排序 */
    private List<TDrainLevelStd> availableAt(Date at) {
        List<TDrainLevelStd> all = this.drainLevelStdMapper.selectList(new QueryWrapper<TDrainLevelStd>());
        List<TDrainLevelStd> out = new ArrayList<TDrainLevelStd>();
        for (TDrainLevelStd r : all) {
            if (alive(r) && inWindow(r, at)) {
                out.add(r);
            }
        }
        out.sort(PICK_ORDER);
        return out;
    }

    /** 未删且未作废才算活着；已作废的老线任何时刻都不许拿来填 */
    private static boolean alive(TDrainLevelStd r) {
        if (r == null) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag() == 1) {
            return false;
        }
        return r.getStatus() == null || r.getStatus() != STATUS_VOID;
    }

    /** 半开区间 [effStart, effEnd)：交接瞬间老版已失效、新版刚生效，当下有效的只能有一版；空端为无界 */
    private static boolean inWindow(TDrainLevelStd r, Date at) {
        if (r.getEffStart() != null && at.before(r.getEffStart())) {
            return false;
        }
        return r.getEffEnd() == null || at.before(r.getEffEnd());
    }

    /** at 文本照 yyyy-MM-dd HH:mm:ss 解析；空串按当下时刻（实时判定主场景），坏格式返回 null */
    private static Date parseAt(String atText) {
        if (atText == null || atText.trim().isEmpty()) {
            return new Date();
        }
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(AT_PATTERN);
            fmt.setLenient(false);
            return fmt.parse(atText.trim());
        } catch (java.text.ParseException e) {
            return null;
        }
    }

    /**
     * 分档：落在 (上一档上限, 本档上限] 取本档，与机组运行评定档同一口径；
     * 为 NULL 的档视作未划线顺次往下着；高出全部已划档归超档(4)。
     */
    private int levelOf(TDrainLevelStd rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
