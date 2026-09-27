package com.fc.v2.model.custom;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 强排调度三级签核节点配置。
 * 顺序固定：值班长(0) -&gt; 排水科(1) -&gt; 分管领导(2)，前一节点不过后节点不许抢跑。
 * 会签节点指定名单写死：排水科三人全部落名才算过，缺一笔/替签都不行。
 *
 * @author fuce
 * @date 2026-09-24
 */
public enum DispatchNode {

    /** 节点0 值班长：或签，指定值班长1人，任一人签即过节点 */
    DUTY_LEAD(0, "值班长", 0, Collections.unmodifiableList(Arrays.asList("duty_lead01"))),

    /** 节点1 排水科：会签，指定三人，三人全部落名才过节点 */
    DRAIN_SECTION(1, "排水科", 1,
            Collections.unmodifiableList(Arrays.asList("drain_ke_zhang", "drain_ke_wang", "drain_ke_li"))),

    /** 节点2 分管领导：或签，指定分管领导1人，签完即批准强排 */
    LEADER(2, "分管领导", 0, Collections.unmodifiableList(Arrays.asList("boss_zhang")));

    /** 签核模式 0或签 1会签 */
    public static final int MODE_OR = 0;
    public static final int MODE_AND = 1;

    private final int nodeNo;
    private final String nodeName;
    private final int signMode;
    private final List<String> roster;

    DispatchNode(int nodeNo, String nodeName, int signMode, List<String> roster) {
        this.nodeNo = nodeNo;
        this.nodeName = nodeName;
        this.signMode = signMode;
        this.roster = roster;
    }

    public int getNodeNo() {
        return nodeNo;
    }

    public String getNodeName() {
        return nodeName;
    }

    public int getSignMode() {
        return signMode;
    }

    public List<String> getRoster() {
        return roster;
    }

    /** 或签1人即过；会签按指定名单全员 */
    public int getNeedCount() {
        return signMode == MODE_AND ? roster.size() : 1;
    }

    public boolean contains(String approver) {
        return approver != null && roster.contains(approver);
    }

    public static DispatchNode of(Integer nodeNo) {
        if (nodeNo == null) {
            throw new IllegalArgumentException("签核节点为空");
        }
        for (DispatchNode n : values()) {
            if (n.nodeNo == nodeNo) {
                return n;
            }
        }
        throw new IllegalArgumentException("非法签核节点: " + nodeNo);
    }
}
