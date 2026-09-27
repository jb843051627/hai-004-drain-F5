package com.fc.v2.model.custom;

/**
 * 机房机组温/湿度表计口径。
 *
 * 这两个表计的日常读数由远传走；只有断传的那些天才许值守员按抄表时间人工补录。
 * 计量点编码按段认（- 或 _ 切开），含 TEMP 段是温度表、含 HUMI 段是湿度表，
 * 如 PS00-TEMP-01、PS00_HUMI_02。其余计量点（电度 EM 等）不走断传补录这条规矩。
 *
 * @author fuce
 * @date 2026-09-26
 */
public enum MeterGaugeType {

    /** 机房机组温度表 */
    TEMP("TEMP", "温度"),

    /** 机房机组湿度表 */
    HUMI("HUMI", "湿度");

    private final String code;
    private final String label;

    MeterGaugeType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 认计量点编码是不是温/湿度表计。按 - 或 _ 切段后整段比 TEMP/HUMI（忽略大小写），
     * 不许子串糊认，避免 EM、TEMPX 之类被当成温度表。
     */
    public boolean matches(String itemCode) {
        if (itemCode == null) {
            return false;
        }
        String[] segs = itemCode.split("[-_]");
        for (String seg : segs) {
            if (code.equalsIgnoreCase(seg)) {
                return true;
            }
        }
        return false;
    }

    /** 是温/湿度表计就返回对应枚举，否则 null（非这两个表计，不进断传补录口径）。 */
    public static MeterGaugeType of(String itemCode) {
        for (MeterGaugeType t : values()) {
            if (t.matches(itemCode)) {
                return t;
            }
        }
        return null;
    }
}
