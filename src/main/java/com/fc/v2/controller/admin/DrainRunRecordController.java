package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.service.ITDrainRunRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

/**
 * 机组运行记录单 Controller
 *
 * @author fuce
 * @date 2026-09-12
 */
@Api(value = "机组运行记录单")
@Controller
@RequestMapping("/DrainRunRecordController")
public class DrainRunRecordController extends BaseController {

    private final String prefix = "admin/drainRunRecord";

    @Autowired
    private ITDrainRunRecordService drainRunRecordService;

    @ApiOperation(value = "分页跳转", notes = "分页跳转")
    @GetMapping("/view")
    @RequiresPermissions("drain:drainRunRecord:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "机组运行记录单集合查询", action = "list")
    @ApiOperation(value = "分页查询", notes = "分页查询")
    @GetMapping("/list")
    @RequiresPermissions("drain:drainRunRecord:list")
    @ResponseBody
    public ResultTable list(TDrainRunRecord record) {
        QueryWrapper<TDrainRunRecord> queryWrapper = new QueryWrapper<TDrainRunRecord>();
        // 状态/单号/站号筛选由页面给；不筛选时待复核/已复核/已封存都摆（服务层只兜底未删）
        if (record.getStatus() != null) {
            queryWrapper.eq("status", record.getStatus());
        }
        if (record.getBillNo() != null && !record.getBillNo().trim().isEmpty()) {
            queryWrapper.like("bill_no", record.getBillNo().trim());
        }
        if (record.getSiteNo() != null && !record.getSiteNo().trim().isEmpty()) {
            queryWrapper.eq("site_no", record.getSiteNo().trim());
        }
        queryWrapper.orderByDesc("create_time", "id");
        startPage();
        com.github.pagehelper.PageInfo<TDrainRunRecord> page =
                new com.github.pagehelper.PageInfo<TDrainRunRecord>(drainRunRecordService.selectTDrainRunRecordList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "机组运行记录单新增", action = "add")
    @ApiOperation(value = "新增", notes = "新增")
    @PostMapping("/add")
    @RequiresPermissions("drain:drainRunRecord:add")
    @ResponseBody
    public AjaxResult add(TDrainRunRecord record) {
        return toAjax(drainRunRecordService.insertTDrainRunRecord(record));
    }

    @Log(title = "机组运行记录单修改", action = "edit")
    @ApiOperation(value = "修改保存", notes = "修改保存")
    @PostMapping("/edit")
    @RequiresPermissions("drain:drainRunRecord:edit")
    @ResponseBody
    public AjaxResult editSave(TDrainRunRecord record) {
        return toAjax(drainRunRecordService.updateTDrainRunRecord(record));
    }

    @Log(title = "机组运行记录单删除", action = "remove")
    @ApiOperation(value = "删除", notes = "删除")
    @DeleteMapping("/remove")
    @RequiresPermissions("drain:drainRunRecord:remove")
    @ResponseBody
    public AjaxResult remove(String ids) {
        return toAjax(drainRunRecordService.deleteTDrainRunRecordByIds(ids));
    }
}
