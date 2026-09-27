package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TDrainRunRecordMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.service.ITDrainRunRecordService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 机组运行记录单Service业务层处理
 *
 * 口径：
 * 1. 录一条即落「待复核(status=0)」记号并写死删除标记 0——不靠库默认值（该列 DEFAULT NULL，
 *    漏写就是 NULL，台账只摆正常单时会当场失踪，现场明明录过却查无此单）；
 * 2. 台账摆全部未删单（待复核/已复核/已封存都摆），查询条件由调用方给，服务层不再替页面
 *    硬塞 status=0；分页也由调用方 startPage() 一处开，服务层不再自行分页（两处都开时
 *    里层 startPage(1,10) 会把页面的页号页长顶掉）；
 * 3. 受录泵站必须是在册在用档案：不存在/已停用不收，站点编号照档案回填，不信表单手填；
 * 4. 评定档照在档案阈值算，阈值允许只维护部分档位：某档上限为 NULL 视作该档未划线，
 *    顺次往下一档着，不许对 null 比较把整单录崩。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TDrainRunRecordServiceImpl extends ServiceImpl<TDrainRunRecordMapper, TDrainRunRecord> implements ITDrainRunRecordService {

    /** 记录单状态 0待复核 1已复核 2已封存，新录一律落待复核 */
    private static final int STATUS_WAIT = 0;

    @Autowired
    private TDrainStationMapper drainStationMapper;

    @Override
    public TDrainRunRecord selectTDrainRunRecordById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TDrainRunRecord>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TDrainRunRecord> selectTDrainRunRecordList(Wrapper<TDrainRunRecord> queryWrapper) {
        QueryWrapper<TDrainRunRecord> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TDrainRunRecord>) queryWrapper
                : new QueryWrapper<TDrainRunRecord>();
        // 台账只摆未删单；状态口径（待复核/已复核/已封存）交调用方按筛选条件决定，不在这里硬塞
        wrapper.eq("del_flag", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTDrainRunRecord(TDrainRunRecord record) {
        if (record == null || record.getSiteId() == null) {
            return 0;
        }

        TDrainStation refArch = drainStationMapper.selectOne(new QueryWrapper<TDrainStation>()
                .eq("id", record.getSiteId()).eq("del_flag", 0));
        if (refArch == null) {
            // 受录泵站档案不存在：不收，避免落一条查无对档的孤单
            return 0;
        }
        if (refArch.getStatus() != null && refArch.getStatus() == 1) {
            // 档案已停用的站不再录新单；停用要在档案里先改回来
            return 0;
        }
        record.setSiteNo(refArch.getSiteNo());
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                    .eq("bill_no", record.getBillNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        record.setGradeLevel(gradeOf(refArch, record.getQty()));

        // 新录即待复核、正常单：显式落记号，不靠列默认值（status 列 DEFAULT NULL）
        record.setStatus(STATUS_WAIT);
        record.setDelFlag(0);
        record.setCreateBy(record.getBillNo());
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTDrainRunRecord(TDrainRunRecord record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                    .eq("bill_no", record.getBillNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TDrainRunRecord>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTDrainRunRecordByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTDrainRunRecordById(Long id) {
        return this.baseMapper.deleteById(id);
    }

    /**
     * 照档案阈值定运行评定档：qty 落在 (上一档上限, 本档上限] 取本档；
     * 某档上限为 NULL 表示该档未划线，顺次往下着；读数缺失或超过全部已划线档归 4 档。
     */
    private int gradeOf(TDrainStation arch, BigDecimal qty) {
        if (qty == null) {
            return 0;
        }
        if (arch.getTh1Max() != null && qty.compareTo(arch.getTh1Max()) <= 0) {
            return 1;
        }
        if (arch.getTh2Max() != null && qty.compareTo(arch.getTh2Max()) <= 0) {
            return 2;
        }
        if (arch.getTh3Max() != null && qty.compareTo(arch.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
