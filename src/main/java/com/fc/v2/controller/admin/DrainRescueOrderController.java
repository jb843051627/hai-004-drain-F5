package com.fc.v2.controller.admin;

import java.util.HashMap;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TDrainRescueOrder;
import com.fc.v2.service.ITDrainRescueOrderService;
import com.fc.v2.shiro.util.ShiroUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 抢险作业单 Controller（state-machine 形状：五档流转入口）
 *
 * 只暴露开单(接单)/推进/撤回重办/退回档重办/补单/台账倒查，不提供自由编辑入口——
 * 办结销号后在任何接口上都改不动、删不掉，确有错漏只能另开补单。
 *
 * @author fuce
 * @date 2026-09-27
 */
@Api(value = "抢险作业单")
@Controller
@RequestMapping("/drainRescueOrder")
public class DrainRescueOrderController extends BaseController {

    @Autowired
    private ITDrainRescueOrderService drainRescueOrderService;

    @Log(title = "抢险作业单流转台账", action = "list")
    @ApiOperation(value = "流转台账", notes = "流转台账列表")
    @GetMapping("/list")
    @RequiresPermissions("drainRescueOrder:list")
    @ResponseBody
    public ResultTable list(TDrainRescueOrder record) {
        QueryWrapper<TDrainRescueOrder> queryWrapper = new QueryWrapper<>();
        if (record.getBizNo() != null && !record.getBizNo().trim().isEmpty()) {
            queryWrapper.like("biz_no", record.getBizNo().trim());
        }
        if (record.getSiteNo() != null && !record.getSiteNo().trim().isEmpty()) {
            queryWrapper.eq("site_no", record.getSiteNo().trim());
        }
        if (record.getStatus() != null) {
            queryWrapper.eq("status", record.getStatus());
        }
        queryWrapper.orderByDesc("create_time");
        startPage();
        com.github.pagehelper.PageInfo<TDrainRescueOrder> page =
                new com.github.pagehelper.PageInfo<>(drainRescueOrderService.selectTDrainRescueOrderList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "抢险作业单详情", action = "detail")
    @ApiOperation(value = "单据详情", notes = "单据当前档与状态")
    @GetMapping("/detail")
    @RequiresPermissions("drainRescueOrder:list")
    @ResponseBody
    public AjaxResult detail(@RequestParam Long id) {
        TDrainRescueOrder order = drainRescueOrderService.selectTDrainRescueOrderById(id);
        if (order == null) {
            return AjaxResult.error("抢险作业单不存在");
        }
        return AjaxResult.successData(200, order);
    }

    @Log(title = "抢险作业单留痕", action = "trail")
    @ApiOperation(value = "档级留痕倒查", notes = "单面只看现行有效笔；trail 含作废笔，一级级倒着盘")
    @GetMapping("/trail")
    @RequiresPermissions("drainRescueOrder:list")
    @ResponseBody
    public AjaxResult trail(@RequestParam Long id,
                            @RequestParam(defaultValue = "false") boolean includeVoid) {
        Map<String, Object> data = new HashMap<>();
        data.put("order", drainRescueOrderService.selectTDrainRescueOrderById(id));
        data.put("steps", includeVoid
                ? drainRescueOrderService.trail(id)
                : drainRescueOrderService.liveTrail(id));
        return AjaxResult.successData(200, data);
    }

    @Log(title = "抢险作业单开单", action = "open")
    @ApiOperation(value = "开单即接单", notes = "落到接单档在办，接单这笔当场进流水")
    @PostMapping("/open")
    @RequiresPermissions("drainRescueOrder:open")
    @ResponseBody
    public AjaxResult open(@RequestParam String siteNo, String detail) {
        TDrainRescueOrder order = drainRescueOrderService.open(
                siteNo, ShiroUtils.getLoginName(), detail);
        return AjaxResult.success("接单成功，抢险作业单号：" + order.getBizNo());
    }

    @Log(title = "抢险作业单推进", action = "advance")
    @ApiOperation(value = "推进一档", notes = "只许往前挪一档；同档不重办挪不出第二回；到档4办结销号")
    @PostMapping("/advance")
    @RequiresPermissions("drainRescueOrder:advance")
    @ResponseBody
    public AjaxResult advance(@RequestParam Long id, String detail) {
        TDrainRescueOrder order = drainRescueOrderService.advance(
                id, ShiroUtils.getLoginName(), detail);
        return AjaxResult.success(order.getStatus() != null && order.getStatus() == 1
                ? "五档已齐，办结销号" : "推进成功");
    }

    @Log(title = "抢险作业单撤回", action = "rollback")
    @ApiOperation(value = "撤回一档重办", notes = "退回目标档及之后的现行办理笔作废，停在退回档")
    @PostMapping("/rollback")
    @RequiresPermissions("drainRescueOrder:rollback")
    @ResponseBody
    public AjaxResult rollback(@RequestParam Long id, String reason) {
        drainRescueOrderService.rollback(id, ShiroUtils.getLoginName(), reason);
        return AjaxResult.success("已撤回到上一档重办，该档及之后的旧记录已作废");
    }

    @Log(title = "抢险作业单重办", action = "redo")
    @ApiOperation(value = "退回档重办", notes = "给退回档落新办理笔，恢复在办后才许推进")
    @PostMapping("/redo")
    @RequiresPermissions("drainRescueOrder:redo")
    @ResponseBody
    public AjaxResult redo(@RequestParam Long id, String detail) {
        drainRescueOrderService.redo(id, ShiroUtils.getLoginName(), detail);
        return AjaxResult.success("退回档已重办，单据恢复在办");
    }

    @Log(title = "抢险作业单补单", action = "supplement")
    @ApiOperation(value = "销号后开补单", notes = "母单一笔不动，补单挂母单从接单重走五档")
    @PostMapping("/supplement")
    @RequiresPermissions("drainRescueOrder:supplement")
    @ResponseBody
    public AjaxResult supplement(@RequestParam Long id, String detail) {
        TDrainRescueOrder sup = drainRescueOrderService.supplement(
                id, ShiroUtils.getLoginName(), detail);
        return AjaxResult.success("补单已开，补单号：" + sup.getBizNo());
    }
}
