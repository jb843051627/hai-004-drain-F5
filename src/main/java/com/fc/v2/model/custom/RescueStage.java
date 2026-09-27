package com.fc.v2.model.custom;

/**
 * 抢险作业单五档配置。
 * 顺序固定：接单(0) -&gt; 到场布防(1) -&gt; 处置(2) -&gt; 撤场(3) -&gt; 办结销号(4)，
 * 一道一道往前挪，不许跨档；办结销号是终态。
 *
 * @author fuce
 * @date 2026-09-27
 */
public enum RescueStage {

    /** 档0 接单：接到抢险工单 */
    ACCEPT(0, "接单"),

    /** 档1 到场布防：到场、警戒锥/围挡布设到位 */
    DEPLOY(1, "到场布防"),

    /** 档2 处置：现场抢险处置 */
    HANDLE(2, "处置"),

    /** 档3 撤场：清点人员装备、撤除布防、恢复交通 */
    WITHDRAW(3, "撤场"),

    /** 档4 办结销号：销号关单，走到此档即终态 */
    CLOSE(4, "办结销号");

    public static final int FIRST_STAGE = 0;
    public static final int LAST_STAGE = 4;

    private final int code;
    private final String name;

    RescueStage(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public static RescueStage of(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("抢险档位为空");
        }
        for (RescueStage s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("非法抢险档位: " + code);
    }
}
