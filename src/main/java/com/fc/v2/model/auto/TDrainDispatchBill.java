package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 泵站强排调度签批单对象 t_drain_dispatch_bill
 *
 * @author fuce
 * @date 2026-09-12
 */
@TableName("t_drain_dispatch_bill")
@ApiModel(value = "TDrainDispatchBill", description = "泵站强排调度签批单")
public class TDrainDispatchBill implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 调度申请号 */
    @TableField("bill_no")
    @ApiModelProperty(value = "调度申请号")
    private String billNo;

    /** 申报轮次 首轮1 被驳回重报进下一轮 */
    @TableField("round_no")
    @ApiModelProperty(value = "申报轮次 首轮1 被驳回重报进下一轮")
    private Integer roundNo;

    /** 当前签核节点 0值班长 1排水科 2分管领导 */
    @TableField("node_no")
    @ApiModelProperty(value = "当前签核节点 0值班长 1排水科 2分管领导")
    private Integer nodeNo;

    /** 签核模式 0或签 1会签 */
    @TableField("sign_mode")
    @ApiModelProperty(value = "签核模式 0或签 1会签")
    private Integer signMode;

    /** 本节点应签人数 */
    @TableField("need_count")
    @ApiModelProperty(value = "本节点应签人数")
    private Integer needCount;

    /** 本节点已签人数 */
    @TableField("sign_count")
    @ApiModelProperty(value = "本节点已签人数")
    private Integer signCount;

    /** 签批单状态 0审批中 1批准强排 2已驳回(本轮终止) */
    @TableField("status")
    @ApiModelProperty(value = "签批单状态 0审批中 1批准强排 2已驳回(本轮终止)")
    private Integer status;

    /** 申请强排规模(m3/h) 批准后冻结 */
    @TableField("pump_scale")
    @ApiModelProperty(value = "申请强排规模(m3/h) 批准后冻结")
    private BigDecimal pumpScale;

    /** 申请开工时刻 批准后冻结 */
    @TableField("start_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "申请开工时刻 批准后冻结")
    private Date startTime;

    /** 删除标记 0正常 1删除 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1删除")
    private Integer delFlag;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    /** 更新者 */
    @TableField(value = "update_by", fill = FieldFill.UPDATE)
    @ApiModelProperty(value = "更新者")
    private String updateBy;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    /** 备注 */
    @TableField("remark")
    @ApiModelProperty(value = "备注")
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
    }

    public Integer getNodeNo() {
        return nodeNo;
    }

    public void setNodeNo(Integer nodeNo) {
        this.nodeNo = nodeNo;
    }

    public Integer getSignMode() {
        return signMode;
    }

    public void setSignMode(Integer signMode) {
        this.signMode = signMode;
    }

    public Integer getNeedCount() {
        return needCount;
    }

    public void setNeedCount(Integer needCount) {
        this.needCount = needCount;
    }

    public Integer getSignCount() {
        return signCount;
    }

    public void setSignCount(Integer signCount) {
        this.signCount = signCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public BigDecimal getPumpScale() {
        return pumpScale;
    }

    public void setPumpScale(BigDecimal pumpScale) {
        this.pumpScale = pumpScale;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
