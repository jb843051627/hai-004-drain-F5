package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.model.auto.TDrainRunRecord;

import java.util.List;

/**
 * 机组运行记录单 Service接口
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface ITDrainRunRecordService {

    /** 按主键查询 */
    TDrainRunRecord selectTDrainRunRecordById(Long id);

    /** 按条件查询列表（分页由调用方统一处理） */
    List<TDrainRunRecord> selectTDrainRunRecordList(Wrapper<TDrainRunRecord> queryWrapper);

    /** 新增 */
    int insertTDrainRunRecord(TDrainRunRecord record);

    /** 修改 */
    int updateTDrainRunRecord(TDrainRunRecord record);

    /** 批量删除 */
    int deleteTDrainRunRecordByIds(String ids);

    /** 按主键删除 */
    int deleteTDrainRunRecordById(Long id);
}
