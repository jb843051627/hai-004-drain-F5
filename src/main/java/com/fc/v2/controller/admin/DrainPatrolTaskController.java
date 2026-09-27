package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TDrainPatrolTask;
import com.fc.v2.model.custom.PatrolRoundResult;
import com.fc.v2.service.ITDrainPatrolTaskService;
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
 * 汛期值守巡检任务条目 Controller（scheduling-job 形状：自动派单，无新增/编辑口子）
 *
 * 页面不替定时任务定结果：只摆台账、照服务层返回原样回显这一轮派成啥样。
 * 到点派给谁、空档派不派、压修单推不推，全是服务层照值守表算的，页面不预设、不改写。
 *
 * @author fuce
 * @date 2026-09-26
 */
@Api(value = "汛期值守巡检任务条目")
@Controller
@RequestMapping("/drainPatrolTask")
public class DrainPatrolTaskController extends BaseController {

    @Autowired
    private ITDrainPatrolTaskService drainPatrolTaskService;

    @Log(title = "巡检派单台账", action = "list")
    @ApiOperation(value = "巡检条目台账", notes = "含待派/已派条目与受派记号，按到期时刻倒序")
    @GetMapping("/list")
    @RequiresPermissions("drainPatrolTask:list")
    @ResponseBody
    public ResultTable list(TDrainPatrolTask query) {
        QueryWrapper<TDrainPatrolTask> wrapper = new QueryWrapper<>();
        wrapper.eq("del_flag", 0);
        if (query.getStatus() != null) {
            wrapper.eq("status", query.getStatus());
        }
        if (query.getItemNo() != null && !query.getItemNo().trim().isEmpty()) {
            wrapper.like("item_no", query.getItemNo().trim());
        }
        if (query.getSiteNo() != null && !query.getSiteNo().trim().isEmpty()) {
            wrapper.eq("site_no", query.getSiteNo().trim());
        }
        wrapper.orderByDesc("due_at", "id");
        startPage();
        com.github.pagehelper.PageInfo<TDrainPatrolTask> page =
                new com.github.pagehelper.PageInfo<>(
                        drainPatrolTaskService.selectTDrainPatrolTaskList(wrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "巡检条目详情", action = "detail")
    @ApiOperation(value = "巡检条目详情", notes = "含受派人、派定时刻、所属班次记号")
    @GetMapping("/detail")
    @RequiresPermissions("drainPatrolTask:list")
    @ResponseBody
    public AjaxResult detail(@RequestParam Long id) {
        TDrainPatrolTask task = drainPatrolTaskService.selectTDrainPatrolTaskById(id);
        if (task == null || (task.getDelFlag() != null && task.getDelFlag() == 1)) {
            return AjaxResult.error("巡检条目不存在");
        }
        return AjaxResult.successData(200, task);
    }

    @Log(title = "巡检派单手动喊一轮", action = "run")
    @ApiOperation(value = "立即跑一轮派单", notes = "页面只负责把任务喊起来，派不派、派给谁认服务返回；"
            + "排班空档/一支派不出都正常返回，坏行在返回里逐笔列出")
    @PostMapping("/run")
    @RequiresPermissions("drainPatrolTask:run")
    @ResponseBody
    public AjaxResult run() {
        PatrolRoundResult result = drainPatrolTaskService.dispatchRound();
        String msg;
        if (!result.isDutyPeriod()) {
            msg = "当前为排班空档，本轮不派";
        } else if (result.getDispatchedCount() == 0) {
            msg = "本轮一支都没派出（后推" + result.getDeferredCount() + "条，坏行"
                    + result.getBadRowCount() + "条）";
        } else {
            msg = "本轮派出" + result.getDispatchedCount() + "条，后推"
                    + result.getDeferredCount() + "条，坏行跳过" + result.getBadRowCount() + "条";
        }
        return AjaxResult.success(200, msg, result);
    }
}
