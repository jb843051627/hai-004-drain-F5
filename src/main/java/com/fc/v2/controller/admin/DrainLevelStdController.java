package com.fc.v2.controller.admin;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.custom.JudgeResult;
import com.fc.v2.service.ITDrainLevelStdService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * 泵站起排判线 Controller（rule-eval 形状：接口直调唯一入口，只读）
 *
 * 判线是起不起排的正式依据，入口只留「接口直调」一个：页面不替接口直调定结论，
 * 这一判定不起排、起几档，全看服务层随返回落下的 JudgeResult——available/level/原由
 * 都在回话里，页面照返回摆，不许自己代填档位。
 * 新增、编辑、删除的口子一律不开（判线版本由正式依据台账另册管理，本控制器不沾写）。
 *
 * @author fuce
 * @date 2026-09-28
 */
@Api(value = "泵站起排判线")
@Controller
@RequestMapping("/drainLevelStd")
public class DrainLevelStdController extends BaseController {

    @Autowired
    private ITDrainLevelStdService drainLevelStdService;

    @Log(title = "泵站起排判定", action = "judge")
    @ApiOperation(value = "起排判定(接口直调)", notes = "水位+时刻交服务层判：回判历史时刻以那一刻有效期内的版本为准，"
            + "湿季/干季交接点半开区间不重叠；同刻叠版照取优序号取头一版；哪版都不沾直回当下无可用判线；"
            + "水位为负或数量级明显不对不收。ruleCode 可不带，带了只在该代号版本里取，取优口径一致。")
    @GetMapping("/judge")
    @RequiresPermissions("drainLevelStd:judge")
    @ResponseBody
    public AjaxResult judge(@RequestParam(required = false) String ruleCode,
                            @RequestParam BigDecimal waterLevel,
                            @RequestParam(required = false) Date at) {
        if (waterLevel == null) {
            return AjaxResult.error("水位不能为空");
        }
        // 判定全在服务层：控制器不预判正负、不预判档位，结果生效与否只看返回
        JudgeResult result = drainLevelStdService.judge(ruleCode, waterLevel, at);
        return AjaxResult.successData(200, result);
    }

    @Log(title = "当下有效判线查", action = "available")
    @ApiOperation(value = "查当下有效判线", notes = "只读：at 时刻(不带按当下)处在有效期内的现行版本，"
            + "照取优序号排序摆；作废老线、窗口没录全的不摆。供看板/角标对账，不替判定下结论。")
    @GetMapping("/available")
    @RequiresPermissions("drainLevelStd:list")
    @ResponseBody
    public AjaxResult available(@RequestParam(required = false) Date at) {
        Date moment = at == null ? new Date() : at;
        Map<String, Object> data = new HashMap<>();
        data.put("at", moment);
        data.put("rules", drainLevelStdService.listAvailable(moment));
        data.put("count", drainLevelStdService.countAvailable(moment));
        return AjaxResult.successData(200, data);
    }
}
