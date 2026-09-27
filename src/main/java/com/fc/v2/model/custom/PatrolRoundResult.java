package com.fc.v2.model.custom;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 巡检自动派单一轮的回话。
 *
 * 页面/定时日志只认这份服务层算出来的实账，不许谁在外面替它定结果：
 * - dutyPeriod=false 表示这轮跑在排班空档，一条不派、也不算出错；
 * - dispatched 派掉的（条目已挂受派人/时刻/班次记号，往后各轮不再进候选）；
 * - deferred 站里压着没销修单、本轮自动后推的（条目保持待派，下轮再择）；
 * - badRows 数据磕坏跳过的行，逐笔留编号与原由交人工核——一行坏不撂下整轮。
 *
 * @author fuce
 * @date 2026-09-26
 */
public class PatrolRoundResult {

    /** 本轮执行时刻 */
    private Date at;

    /** 是否落在值守时段内（空档=false，不派不吭声） */
    private boolean dutyPeriod;

    /** 锚定的值守排班（空档为 null；重排时取起时最早、id 最小的一条） */
    private Long shiftId;
    private String shiftName;
    private String dutyUser;

    /** 到点候选条数 */
    private int candidateCount;

    /** 实际派出条数 */
    private int dispatchedCount;

    /** 压着未销修单、本轮后推条数 */
    private int deferredCount;

    /** 磕坏跳过的行数 */
    private int badRowCount;

    private final List<DispatchedItem> dispatched = new ArrayList<>();
    private final List<DeferredItem> deferred = new ArrayList<>();
    private final List<BadRow> badRows = new ArrayList<>();

    public static PatrolRoundResult idleRound(Date at) {
        PatrolRoundResult r = new PatrolRoundResult();
        r.at = at;
        r.dutyPeriod = false;
        return r;
    }

    public void addDispatched(DispatchedItem item) {
        this.dispatched.add(item);
        this.dispatchedCount = this.dispatched.size();
    }

    public void addDeferred(DeferredItem item) {
        this.deferred.add(item);
        this.deferredCount = this.deferred.size();
    }

    public void addBadRow(BadRow row) {
        this.badRows.add(row);
        this.badRowCount = this.badRows.size();
    }

    public Date getAt() {
        return at;
    }

    public void setAt(Date at) {
        this.at = at;
    }

    public boolean isDutyPeriod() {
        return dutyPeriod;
    }

    public void setDutyPeriod(boolean dutyPeriod) {
        this.dutyPeriod = dutyPeriod;
    }

    public Long getShiftId() {
        return shiftId;
    }

    public void setShiftId(Long shiftId) {
        this.shiftId = shiftId;
    }

    public String getShiftName() {
        return shiftName;
    }

    public void setShiftName(String shiftName) {
        this.shiftName = shiftName;
    }

    public String getDutyUser() {
        return dutyUser;
    }

    public void setDutyUser(String dutyUser) {
        this.dutyUser = dutyUser;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }

    public int getDispatchedCount() {
        return dispatchedCount;
    }

    public int getDeferredCount() {
        return deferredCount;
    }

    public int getBadRowCount() {
        return badRowCount;
    }

    public List<DispatchedItem> getDispatched() {
        return dispatched;
    }

    public List<DeferredItem> getDeferred() {
        return deferred;
    }

    public List<BadRow> getBadRows() {
        return badRows;
    }

    /** 一条派定明细：哪条巡检、哪个站、交给了谁。 */
    public static class DispatchedItem {
        private final Long taskId;
        private final String itemNo;
        private final String siteNo;
        private final String assignee;

        public DispatchedItem(Long taskId, String itemNo, String siteNo, String assignee) {
            this.taskId = taskId;
            this.itemNo = itemNo;
            this.siteNo = siteNo;
            this.assignee = assignee;
        }

        public Long getTaskId() {
            return taskId;
        }

        public String getItemNo() {
            return itemNo;
        }

        public String getSiteNo() {
            return siteNo;
        }

        public String getAssignee() {
            return assignee;
        }
    }

    /** 一条后推明细：站里压着哪号没销的修单。 */
    public static class DeferredItem {
        private final Long taskId;
        private final String itemNo;
        private final String siteNo;
        private final String reason;

        public DeferredItem(Long taskId, String itemNo, String siteNo, String reason) {
            this.taskId = taskId;
            this.itemNo = itemNo;
            this.siteNo = siteNo;
            this.reason = reason;
        }

        public Long getTaskId() {
            return taskId;
        }

        public String getItemNo() {
            return itemNo;
        }

        public String getSiteNo() {
            return siteNo;
        }

        public String getReason() {
            return reason;
        }
    }

    /** 一行磕坏数据：留条目编号、站号与原由，回头照这个核。 */
    public static class BadRow {
        private final Long taskId;
        private final String itemNo;
        private final String siteNo;
        private final String reason;

        public BadRow(Long taskId, String itemNo, String siteNo, String reason) {
            this.taskId = taskId;
            this.itemNo = itemNo;
            this.siteNo = siteNo;
            this.reason = reason;
        }

        public Long getTaskId() {
            return taskId;
        }

        public String getItemNo() {
            return itemNo;
        }

        public String getSiteNo() {
            return siteNo;
        }

        public String getReason() {
            return reason;
        }
    }
}
