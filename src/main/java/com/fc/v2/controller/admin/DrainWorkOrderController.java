package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TDrainWorkOrder;
import com.fc.v2.service.ITDrainWorkOrderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

/**
 * 排涝抢险作业单 Controller（state-machine 形状：流转入口）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Api(value = "排涝抢险作业单")
@Controller
@RequestMapping("/drainWorkOrder")
public class DrainWorkOrderController extends BaseController {

    private final String prefix = "admin/drainWorkOrder";

    @Autowired
    private ITDrainWorkOrderService drainWorkOrderService;

    @ApiOperation(value = "流转台账跳转", notes = "流转台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("drainWorkOrder:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "排涝抢险作业单流转台账", action = "list")
    @ApiOperation(value = "流转台账", notes = "流转台账")
    @GetMapping("/list")
    @RequiresPermissions("drainWorkOrder:list")
    @ResponseBody
    public ResultTable list(TDrainWorkOrder record) {
        QueryWrapper<TDrainWorkOrder> queryWrapper = new QueryWrapper<TDrainWorkOrder>();
        startPage();
        com.github.pagehelper.PageInfo<TDrainWorkOrder> page =
                new com.github.pagehelper.PageInfo<TDrainWorkOrder>(drainWorkOrderService.selectTDrainWorkOrderList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "排涝抢险作业单推进", action = "advance")
    @ApiOperation(value = "推进一档", notes = "推进一档")
    @PostMapping("/advance")
    @RequiresPermissions("drainWorkOrder:advance")
    @ResponseBody
    public AjaxResult advance(Long id, String remark) {
        return toAjax(drainWorkOrderService.advance(id, remark) != null ? 1 : 0);
    }

    @Log(title = "排涝抢险作业单回退", action = "rollback")
    @ApiOperation(value = "回退一档", notes = "回退一档")
    @PostMapping("/rollback")
    @RequiresPermissions("drainWorkOrder:rollback")
    @ResponseBody
    public AjaxResult rollback(Long id, String remark) {
        return toAjax(drainWorkOrderService.rollback(id, remark) != null ? 1 : 0);
    }
}
