package com.fc.v2.controller.admin;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TDrainDispatchBill;
import com.fc.v2.service.ITDrainDispatchBillService;
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

import java.math.BigDecimal;

/**
 * 泵站强排调度签批单 Controller（approval-chain 形状：三节点签批入口）
 *
 * 只暴露申报/签批/驳回/重报/台账倒查，不提供自由编辑入口——
 * 批准后强排规模与开工时刻在任何接口上都改不动。
 *
 * @author fuce
 * @date 2026-09-24
 */
@Api(value = "泵站强排调度签批单")
@Controller
@RequestMapping("/drainDispatchBill")
public class DrainDispatchBillController extends BaseController {

    @Autowired
    private ITDrainDispatchBillService drainDispatchBillService;

    @Log(title = "强排调度签批台账", action = "list")
    @ApiOperation(value = "签批台账", notes = "签批台账列表")
    @GetMapping("/list")
    @RequiresPermissions("drainDispatchBill:list")
    @ResponseBody
    public ResultTable list(TDrainDispatchBill record) {
        QueryWrapper<TDrainDispatchBill> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("del_flag", 0);
        if (record.getBillNo() != null && !record.getBillNo().trim().isEmpty()) {
            queryWrapper.like("bill_no", record.getBillNo().trim());
        }
        if (record.getStatus() != null) {
            queryWrapper.eq("status", record.getStatus());
        }
        queryWrapper.orderByDesc("create_time");
        startPage();
        com.github.pagehelper.PageInfo<TDrainDispatchBill> page =
                new com.github.pagehelper.PageInfo<>(drainDispatchBillService.selectTDrainDispatchBillList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "强排调度签批单", action = "detail")
    @ApiOperation(value = "单据详情", notes = "含实时重算的本节点已签数")
    @GetMapping("/detail")
    @RequiresPermissions("drainDispatchBill:list")
    @ResponseBody
    public AjaxResult detail(@RequestParam Long id) {
        TDrainDispatchBill bill = drainDispatchBillService.selectTDrainDispatchBillById(id);
        if (bill == null || (bill.getDelFlag() != null && bill.getDelFlag() == 1)) {
            return AjaxResult.error("签批单不存在");
        }
        return AjaxResult.successData(200, bill);
    }

    @Log(title = "强排调度签批留痕", action = "trail")
    @ApiOperation(value = "签批留痕倒查", notes = "哪个节点谁签的、什么时刻、批了哪一句，倒序返回，含历史轮次")
    @GetMapping("/trail")
    @RequiresPermissions("drainDispatchBill:list")
    @ResponseBody
    public AjaxResult trail(@RequestParam Long id) {
        Map<String, Object> data = new HashMap<>();
        data.put("bill", drainDispatchBillService.selectTDrainDispatchBillById(id));
        data.put("signs", drainDispatchBillService.trail(id));
        return AjaxResult.successData(200, data);
    }

    @Log(title = "强排调度申报", action = "submit")
    @ApiOperation(value = "申报强排", notes = "填报强排规模与开工时刻，进值班长节点")
    @PostMapping("/submit")
    @RequiresPermissions("drainDispatchBill:submit")
    @ResponseBody
    public AjaxResult submit(@RequestParam BigDecimal pumpScale,
                             @RequestParam Date startTime,
                             String remark) {
        TDrainDispatchBill form = new TDrainDispatchBill();
        form.setPumpScale(pumpScale);
        form.setStartTime(startTime);
        form.setRemark(remark);
        TDrainDispatchBill bill = drainDispatchBillService.submit(form);
        return AjaxResult.success("申报成功，调度申请号：" + bill.getBillNo());
    }

    @Log(title = "强排调度签批", action = "approve")
    @ApiOperation(value = "签同意", notes = "当前节点指定签批人签同意；会签需全员落名")
    @PostMapping("/approve")
    @RequiresPermissions("drainDispatchBill:approve")
    @ResponseBody
    public AjaxResult approve(@RequestParam Long id, String comment) {
        TDrainDispatchBill bill = drainDispatchBillService.approve(id, ShiroUtils.getLoginName(), comment);
        return AjaxResult.success(bill.getStatus() != null && bill.getStatus() == 1
                ? "三节点已齐，批准强排" : "签批成功");
    }

    @Log(title = "强排调度驳回", action = "reject")
    @ApiOperation(value = "驳回", notes = "任一指定签批人驳回即终止，后面不许再签")
    @PostMapping("/reject")
    @RequiresPermissions("drainDispatchBill:reject")
    @ResponseBody
    public AjaxResult reject(@RequestParam Long id, String comment) {
        drainDispatchBillService.reject(id, ShiroUtils.getLoginName(), comment);
        return AjaxResult.success("已驳回，本单终止");
    }

    @Log(title = "强排调度驳回重报", action = "resubmit")
    @ApiOperation(value = "驳回后重报", notes = "进下一轮从值班长重走，上一轮批数清零，旧痕保留")
    @PostMapping("/resubmit")
    @RequiresPermissions("drainDispatchBill:resubmit")
    @ResponseBody
    public AjaxResult resubmit(@RequestParam Long id,
                               @RequestParam BigDecimal pumpScale,
                               @RequestParam Date startTime,
                               String comment) {
        drainDispatchBillService.resubmit(id, pumpScale, startTime, comment);
        return AjaxResult.success("已重报，从上节点重走，上轮签批数清零");
    }
}
