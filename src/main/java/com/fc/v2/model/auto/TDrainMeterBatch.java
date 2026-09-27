package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * 远传计量成批汇交批次头 t_drain_meter_batch
 *
 * 一个批次号一行，是同一批交两遍的幂等闸门：并发重交只有头一份插得进，
 * 后一份按头一笔的计数回话，明细行数量不会凭空翻倍。
 *
 * @author fuce
 * @date 2026-09-26
 */
@TableName("t_drain_meter_batch")
@ApiModel(value = "TDrainMeterBatch", description = "远传计量成批汇交批次头")
public class TDrainMeterBatch implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 汇交批次号（主键，幂等凭据） */
    @TableId(value = "batch_no", type = IdType.INPUT)
    @ApiModelProperty(value = "汇交批次号")
    private String batchNo;

    /** 报文原行数 */
    @TableField("total_count")
    @ApiModelProperty(value = "报文原行数")
    private Integer totalCount;

    /** 已对平行数（按头一笔回话） */
    @TableField("ok_count")
    @ApiModelProperty(value = "已对平行数")
    private Integer okCount;

    /** 已剔除行数（按头一笔回话） */
    @TableField("fail_count")
    @ApiModelProperty(value = "已剔除行数")
    private Integer failCount;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间（头一笔落库时刻） */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getOkCount() {
        return okCount;
    }

    public void setOkCount(Integer okCount) {
        this.okCount = okCount;
    }

    public Integer getFailCount() {
        return failCount;
    }

    public void setFailCount(Integer failCount) {
        this.failCount = failCount;
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
}
