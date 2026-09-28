package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TDrainLevelStdMapper;
import com.fc.v2.model.auto.TDrainLevelStd;
import com.fc.v2.model.custom.JudgeResult;
import com.fc.v2.service.ITDrainLevelStdService;

/**
 * 泵站起排判线标准 Service业务层处理（rule-eval 形状）
 *
 * 取版口径只有一套，judge / evaluate / evaluateTop / usable / 列表角标全走它：
 * 1. 版本窗口半开 [eff_start, eff_end)，只认 status=0 现行、del_flag=0、窗口录全的版本；
 *    切换那一刻旧版 end 不含、新版 start 含，当下有效的只能有一版；
 *    哪版都不沾直回"当下无可用判线"，作废老线不许顶。
 * 2. 同刻叠版照取优序号 priority 降序，并列 ruleCode 降序、id 升序——SQL 已排死，
 *    指不指定代号两路取的必须同一版。
 * 3. 分档顺次往下着：某档上限为 NULL 视作没录这一档（早年只录前两档的存量线照旧能用），
 *    水位落在 (上一已划线档上限, 本档上限] 取本档，超全部已划线档归 4。
 * 4. 版本自身阈值为负、数量级明显不对或倒挂的，迁移脏数据，不收不判。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TDrainLevelStdServiceImpl implements ITDrainLevelStdService {

    /** 水位/阈值合理量级上限（米）。泵站液位到这个数量级必是坏值或录错单位，不收。 */
    private static final BigDecimal MAX_SANE = new BigDecimal("100");

    @javax.annotation.Resource
    private TDrainLevelStdMapper drainLevelStdMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：
     * JDBC 的 serverTimezone 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
     * 使边界判定在"湿季/干季切换当日"这类用例上错档（与值守排班同口径）。
     */
    private static String ts(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    private static Date moment(Date at) {
        return at == null ? new Date() : at;
    }

    @Override
    public TDrainLevelStd selectTDrainLevelStdById(Long id) {
        return this.drainLevelStdMapper.selectById(id);
    }

    // ------------------------------------------------------------------ 判定（唯一结论形状）

    @Override
    public JudgeResult judge(BigDecimal waterLevel, Date at) {
        return judge(null, waterLevel, at);
    }

    @Override
    public JudgeResult judge(String ruleCode, BigDecimal waterLevel, Date at) {
        Date moment = moment(at);
        if (!validWaterLevel(waterLevel)) {
            // 负数、数量级明显不对的水位不收，不做判定——绝不拿坏水位去够任何一条线
            return JudgeResult.badInput(waterLevel, moment, badWaterReason(waterLevel));
        }
        TDrainLevelStd rule = pickRule(ruleCode, moment);
        if (rule == null) {
            // 那一刻哪版都不沾：直回"当下无可用判线"，不许拿作废的老线填
            return JudgeResult.noRule(waterLevel, moment);
        }
        return JudgeResult.judged(rule, waterLevel, moment, levelOf(rule, waterLevel));
    }

    // ------------------------------------------------------------------ 老签名（保留，口径同上）

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        JudgeResult r = judge(ruleCode.trim(), input, at);
        return r.isAvailable() && r.getLevel() != null ? r.getLevel() : 0;
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        JudgeResult r = judge(input, at);
        return r.isAvailable() && r.getLevel() != null ? r.getLevel() : 0;
    }

    // ------------------------------------------------------------------ 旁路查询

    @Override
    public List<TDrainLevelStd> listAvailable(Date at) {
        List<TDrainLevelStd> rows = this.drainLevelStdMapper.listEffective(ts(moment(at)));
        if (rows == null) {
            return new ArrayList<>();
        }
        // SQL 已照取优口径排死，这里只剔阈值磕坏的迁移脏版本（不动次序）
        List<TDrainLevelStd> ok = new ArrayList<>(rows.size());
        for (TDrainLevelStd r : rows) {
            if (thresholdsAcceptable(r)) {
                ok.add(r);
            }
        }
        return ok;
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        for (TDrainLevelStd r : listAvailable(at)) {
            if (id.equals(r.getId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int countAvailable(Date at) {
        return listAvailable(at).size();
    }

    // ------------------------------------------------------------------ 取版 / 分档

    /**
     * 唯一取版入口：at 时刻生效版本已由 SQL 按 priority 降序、ruleCode 降序、id 升序排好；
     * 指定代号只在同一序上滤，绝不另起一套挑法——两路取的必须同一版。
     */
    private TDrainLevelStd pickRule(String ruleCode, Date at) {
        String code = ruleCode == null ? null : ruleCode.trim();
        boolean byCode = code != null && !code.isEmpty();
        for (TDrainLevelStd r : listAvailable(at)) {
            if (!byCode || code.equals(r.getRuleCode())) {
                return r;
            }
        }
        return null;
    }

    /**
     * 分档：已划线档顺次往下着，等于上限取本档（口径与运行评定 gradeOf 一致）；
     * 超全部已划线档归 4。取版前已过 thresholdsAcceptable，这里遇 null 直接跳过该档。
     */
    private int levelOf(TDrainLevelStd rule, BigDecimal input) {
        BigDecimal[] limits = new BigDecimal[] {rule.getTh1Max(), rule.getTh2Max(), rule.getTh3Max()};
        int[] tiers = new int[] {1, 2, 3};
        for (int i = 0; i < limits.length; i++) {
            if (limits[i] != null && input.compareTo(limits[i]) <= 0) {
                return tiers[i];
            }
        }
        return 4;
    }

    /** 水位入参合法性：不收负数，不收数量级明显不对的值。 */
    private boolean validWaterLevel(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) >= 0 && v.compareTo(MAX_SANE) <= 0;
    }

    private String badWaterReason(BigDecimal v) {
        if (v == null) {
            return "水位为空，无法判定";
        }
        if (v.compareTo(BigDecimal.ZERO) < 0) {
            return "水位为负，数据磕坏，不收";
        }
        return "水位数量级明显不对（超过" + MAX_SANE.toPlainString() + "米），不收";
    }

    /**
     * 版本阈值体检：早年只录前两档（th3 为 NULL）照旧能用；
     * 但已录的档位不许为负、不许超合理量级、不许倒挂——磕坏的迁移版本整版不收。
     */
    private boolean thresholdsAcceptable(TDrainLevelStd r) {
        BigDecimal prev = null;
        BigDecimal[] limits = new BigDecimal[] {r.getTh1Max(), r.getTh2Max(), r.getTh3Max()};
        for (BigDecimal v : limits) {
            if (v == null) {
                continue;
            }
            if (v.compareTo(BigDecimal.ZERO) < 0 || v.compareTo(MAX_SANE) > 0) {
                return false;
            }
            if (prev != null && v.compareTo(prev) < 0) {
                return false;
            }
            prev = v;
        }
        return true;
    }
}
