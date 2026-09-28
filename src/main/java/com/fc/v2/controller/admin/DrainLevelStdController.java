package com.fc.v2.controller.admin;

import java.math.BigDecimal;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.custom.LevelJudgeResult;
import com.fc.v2.service.ITDrainLevelStdService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 泵站起排判线 Controller（接口直调：只判定，唯一入口）
 *
 * 起不起排不靠人喊，判线是正式依据。这里只留接口直调一个口子：
 * 1. 判定整活儿交服务层——用哪一版、判几档、有没有可用判线，全看返回，页面不替接口定结论；
 * 2. 不开新增、编辑、删除，也没有台账页面（判线版本另有正式落库渠道，不经此口）；
 * 3. 回判历史时刻：at 照 yyyy-MM-dd HH:mm:ss 传那一刻；不传按当下。
 *
 * @author fuce
 * @date 2026-09-27
 */
@Api(value = "泵站起排判线")
@Controller
@RequestMapping("/drainLevelStd")
public class DrainLevelStdController extends BaseController {

    @Autowired
    private ITDrainLevelStdService drainLevelStdService;

    @Log(title = "起排判定-按判线代号", action = "evaluate")
    @ApiOperation(value = "按判线代号判定起排档", notes = "ruleCode+水位+时刻直调；"
            + "返回命中版本与判定档；那一刻该代号哪版都不沾，available=false=当下无可用判线；"
            + "水位为负/数量级离谱整笔不收")
    @GetMapping("/evaluate")
    @ResponseBody
    public AjaxResult evaluate(
            @ApiParam(value = "判线代号", required = true) @RequestParam String ruleCode,
            @ApiParam(value = "当下水位(米)", required = true) @RequestParam BigDecimal qty,
            @ApiParam(value = "判定时刻 yyyy-MM-dd HH:mm:ss，不传按当下") @RequestParam(required = false) String at) {
        LevelJudgeResult result = drainLevelStdService.evaluate(ruleCode, qty, at);
        return toAjax(result);
    }

    @Log(title = "起排判定-取最优判线", action = "evaluateTop")
    @ApiOperation(value = "取最优判线判定起排档", notes = "不指定代号，at 时刻所有有效判线照取优序号取一版；"
            + "与按代号判定共用同一把取优尺子；哪版都不沾 available=false；水位不合法整笔不收")
    @GetMapping("/evaluateTop")
    @ResponseBody
    public AjaxResult evaluateTop(
            @ApiParam(value = "当下水位(米)", required = true) @RequestParam BigDecimal qty,
            @ApiParam(value = "判定时刻 yyyy-MM-dd HH:mm:ss，不传按当下") @RequestParam(required = false) String at) {
        LevelJudgeResult result = drainLevelStdService.evaluateTop(qty, at);
        return toAjax(result);
    }

    /** 判定回话转统一返回体：输入不收算调用错误；收下就照实回话（含「无可用判线」） */
    private AjaxResult toAjax(LevelJudgeResult result) {
        if (!result.isAccepted()) {
            return AjaxResult.error(result.getMessage());
        }
        AjaxResult json = AjaxResult.success();
        json.put("data", result);
        return json;
    }
}
