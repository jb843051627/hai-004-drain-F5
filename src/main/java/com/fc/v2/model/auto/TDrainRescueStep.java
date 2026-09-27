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
import java.util.Date;

/**
 * 抢险作业单档级流水对象 t_drain_rescue_step
 * 每挪一档落一笔：哪一档、第几回办、谁办的、什么时刻、办了什么；
 * 撤回重办时退回档的旧笔置作废（void_flag=1），不与重办新笔并排挂在单面上。
 *
 * @author fuce
 * @date 2026-09-27
 */
@TableName("t_drain_rescue_step")
@ApiModel(value = "TDrainRescueStep", description = "抢险作业单档级流水")
public class TDrainRescueStep implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 抢险作业单主键 t_drain_rescue_order.id */
    @TableField("order_id")
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "抢险作业单主键")
    private Long orderId;

    /** 抢险作业单号(冗余便于台账倒查) */
    @TableField("biz_no")
    @ApiModelProperty(value = "抢险作业单号")
    private String bizNo;

    /** 档位 0接单 1到场布防 2处置 3撤场 4办结销号 */
    @TableField("stage")
    @ApiModelProperty(value = "档位 0接单 1到场布防 2处置 3撤场 4办结销号")
    private Integer stage;

    /** 本档第几回办 首办1 撤回重办一次+1 */
    @TableField("attempt_no")
    @ApiModelProperty(value = "本档第几回办 首办1 撤回重办一次+1")
    private Integer attemptNo;

    /** 经办人登录名 */
    @TableField("operator")
    @ApiModelProperty(value = "经办人登录名")
    private String operator;

    /** 动作 1办理(撤回作废不另起行,看 void_flag/void_by/void_time/void_reason) */
    @TableField("action")
    @ApiModelProperty(value = "动作 1办理")
    private Integer action;

    /** 本档办了什么(办理记录) */
    @TableField("detail")
    @ApiModelProperty(value = "本档办了什么(办理记录)")
    private String detail;

    /** 经办时刻 */
    @TableField("op_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "经办时刻")
    private Date opTime;

    /** 单据全局时序(倒查排序用) */
    @TableField("seq_no")
    @ApiModelProperty(value = "单据全局时序(倒查排序用)")
    private Integer seqNo;

    /** 作废标记 0现行 1已作废 */
    @TableField("void_flag")
    @ApiModelProperty(value = "作废标记 0现行 1已作废")
    private Integer voidFlag;

    /** 作废经办人(谁撤的) */
    @TableField("void_by")
    @ApiModelProperty(value = "作废经办人(谁撤的)")
    private String voidBy;

    /** 作废时刻 */
    @TableField("void_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "作废时刻")
    private Date voidTime;

    /** 作废缘由(撤回批语) */
    @TableField("void_reason")
    @ApiModelProperty(value = "作废缘由(撤回批语)")
    private String voidReason;

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

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public Integer getStage() {
        return stage;
    }

    public void setStage(Integer stage) {
        this.stage = stage;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Integer getAction() {
        return action;
    }

    public void setAction(Integer action) {
        this.action = action;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Date getOpTime() {
        return opTime;
    }

    public void setOpTime(Date opTime) {
        this.opTime = opTime;
    }

    public Integer getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Integer seqNo) {
        this.seqNo = seqNo;
    }

    public Integer getVoidFlag() {
        return voidFlag;
    }

    public void setVoidFlag(Integer voidFlag) {
        this.voidFlag = voidFlag;
    }

    public String getVoidBy() {
        return voidBy;
    }

    public void setVoidBy(String voidBy) {
        this.voidBy = voidBy;
    }

    public Date getVoidTime() {
        return voidTime;
    }

    public void setVoidTime(Date voidTime) {
        this.voidTime = voidTime;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
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
