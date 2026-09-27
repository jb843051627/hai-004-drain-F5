package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TDrainStation;

import java.util.List;

/**
 * 在册泵站档案 Service接口
 *
 * @author fuce
 * @date 2026-09-26
 */
public interface ITDrainStationService {

    /** 按主键查询（已逻辑删除的查不到） */
    TDrainStation selectTDrainStationById(Long id);

    /** 台账：只摆未删档案，条件由调用方给 */
    List<TDrainStation> selectTDrainStationList(QueryWrapper<TDrainStation> queryWrapper);

    /** 新增在册档案 */
    int insertTDrainStation(TDrainStation station);

    /** 编辑档案（名称/阈值/状态都从这条口改） */
    int updateTDrainStation(TDrainStation station);

    /** 改档案状态：0在用 1已停用 */
    int changeStatus(Long id, Integer status);
}
