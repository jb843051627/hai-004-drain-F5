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
 * 抢险作业单对象 t_drain_rescue_order
 * 五档流转：接单(0)-&gt;到场布防(1)-&gt;处置(2)-&gt;撤场(3)-&gt;办结销号(4)。
 * 主表只记当前停在哪一档、什么状态；每档经办人与时刻在流水表逐笔落账。
 *
 * @author fuce
 * @date 2026-09-27
 */
@TableName("t_drain_rescue_order")
@ApiModel(value = "TDrainRescueOrder", description = "抢险作业单(五档流转)")
public class TDrainRescueOrder implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 抢险作业单号 */
    @TableField("biz_no")
    @ApiModelProperty(value = "抢险作业单号")
    private String bizNo;

    /** 抢险所压泵站编号 */
    @TableField("site_no")
    @ApiModelProperty(value = "抢险所压泵站编号")
    private String siteNo;

    /** 当前档 0接单 1到场布防 2处置 3撤场 4办结销号 */
    @TableField("stage")
    @ApiModelProperty(value = "当前档 0接单 1到场布防 2处置 3撤场 4办结销号")
    private Integer stage;

    /** 作业单状态 0在办 1已销号(终态) 2退回重办 */
    @TableField("status")
    @ApiModelProperty(value = "作业单状态 0在办 1已销号(终态) 2退回重办")
    private Integer status;

    /** 补单所补母单主键(仅补单有值;母单本身此列为空) */
    @TableField("supplement_of")
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "补单所补母单主键")
    private Long supplementOf;

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

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getSiteNo() {
        return siteNo;
    }

    public void setSiteNo(String siteNo) {
        this.siteNo = siteNo;
    }

    public Integer getStage() {
        return stage;
    }

    public void setStage(Integer stage) {
        this.stage = stage;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getSupplementOf() {
        return supplementOf;
    }

    public void setSupplementOf(Long supplementOf) {
        this.supplementOf = supplementOf;
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
