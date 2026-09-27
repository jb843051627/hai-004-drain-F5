package com.fc.v2.controller.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TDrainMeterRow;
import com.fc.v2.model.custom.MeterGaugeType;
import com.fc.v2.service.ITDrainMeterRowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 泵站远传计量明细行 Controller（batch-process 形状：成批汇交唯一入口）
 *
 * 写入口只有「成批」一处：逐行怎么对、哪行剔哪行收，明细都在服务层；
 * 这一批算出什么、成没成，以系统推算和成批的返回为准。
 * 不开单条新增、编辑、删除的口子。
 *
 * @author fuce
 * @date 2026-09-25
 */
@Api(value = "泵站远传计量明细行")
@Controller
@RequestMapping("/drainMeterRow")
public class DrainMeterRowController extends BaseController {

    @Autowired
    private ITDrainMeterRowService drainMeterRowService;

    @Autowired
    private ObjectMapper objectMapper;

    @Log(title = "远传计量成批汇交", action = "submitBatch")
    @ApiOperation(value = "成批汇交", notes = "整批提交逐行对账：对平的收下，不平的剔除并留报文行次与原由；"
            + "同一批次号以先落库的那份为准；超单批上限整批不收；空批不算提交成功")
    @PostMapping("/submitBatch")
    @RequiresPermissions("drainMeterRow:submit")
    @ResponseBody
    public AjaxResult submitBatch(@RequestParam String batchNo,
                                  @RequestBody(required = false) JsonNode root) {
        // 空批在调服务之前先挡：报文压根没带体（root=null）或一行都没有，都不算提交成功，
        // 不往服务层、不往库里走，更不能让空体在反序列化/落库环节抛异常崩出来
        if (root == null || root.isNull()) {
            return AjaxResult.error("空批次：一行都没有，不算提交成功");
        }
        if (batchNo == null || batchNo.trim().isEmpty()) {
            return AjaxResult.error("批次号不能为空");
        }
        String no = batchNo.trim();

        // 报文先按原始 JSON 树收下来再逐行转换（Jackson 按 JSON 自身 UTF-8 编码解析，不踩
        // StringHttpMessageConverter 的 ISO-8859-1 坑）：某一行读数不是数字之类的坏值，
        // 只让那一行进剔除明细，绝不能在整包反序列化阶段把同批的好行一并退回
        if (!root.isArray()) {
            return AjaxResult.error("报文应为明细行数组，整批未收");
        }
        List<TDrainMeterRow> rows = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            if (node == null || node.isNull() || !node.isObject()) {
                TDrainMeterRow blank = new TDrainMeterRow();
                blank.setParseError("报文行不是有效明细对象");
                rows.add(blank);
                continue;
            }
            try {
                rows.add(objectMapper.treeToValue(node, TDrainMeterRow.class));
            } catch (JsonProcessingException bad) {
                // 坏值单独记一笔，行次能取就带上，取不到服务层按批内位置补
                TDrainMeterRow broken = new TDrainMeterRow();
                JsonNode rowNoNode = node.get("rowNo");
                if (rowNoNode != null && rowNoNode.canConvertToInt()) {
                    broken.setRowNo(rowNoNode.intValue());
                }
                JsonNode itemNode = node.get("itemCode");
                if (itemNode != null && itemNode.isTextual()) {
                    broken.setItemCode(itemNode.asText());
                }
                broken.setParseError("报文行字段无法识别：" + bad.getOriginalMessage());
                rows.add(broken);
            }
        }
        if (rows.isEmpty()) {
            return AjaxResult.error("空批次：一行都没有，不算提交成功");
        }

        int ok = drainMeterRowService.submitBatch(no, rows);
        if (ok < 0) {
            return AjaxResult.error("一次汇交行数超过单批上限，整批未收，请拆分后再交");
        }
        // 剔除明细照库里的实笔回话；重复批次时就是先落库那份的明细，两头口径一致
        List<TDrainMeterRow> errors = drainMeterRowService.listErrors(no);
        Map<String, Object> data = new HashMap<>();
        data.put("batchNo", no);
        data.put("okCount", ok);
        data.put("errorCount", errors.size());
        data.put("errors", errors);
        return AjaxResult.success(200, "本批收下" + ok + "行，剔除" + errors.size() + "行", data);
    }

    @Log(title = "远传计量按批次查", action = "listByBatch")
    @ApiOperation(value = "按批次查对账结果", notes = "该批次收下与剔除的行都在，按报文行次升序")
    @GetMapping("/listByBatch")
    @RequiresPermissions("drainMeterRow:list")
    @ResponseBody
    public AjaxResult listByBatch(@RequestParam String batchNo) {
        Map<String, Object> data = new HashMap<>();
        data.put("rows", drainMeterRowService.listByBatch(batchNo));
        data.put("errors", drainMeterRowService.listErrors(batchNo));
        return AjaxResult.successData(200, data);
    }

    @Log(title = "远传计量按计量点查", action = "listByItem")
    @ApiOperation(value = "按计量点查", notes = "该计量点跨批次的全部行，按落库先后升序；与按批次查同一口径")
    @GetMapping("/listByItem")
    @RequiresPermissions("drainMeterRow:list")
    @ResponseBody
    public AjaxResult listByItem(@RequestParam String itemCode) {
        return AjaxResult.successData(200, drainMeterRowService.listByItemCode(itemCode));
    }

    @Log(title = "远传计量剔除明细", action = "errors")
    @ApiOperation(value = "批次剔除明细", notes = "该批次被剔除的行，带报文行次与不平原由，运行班照行次回找坏段")
    @GetMapping("/errors")
    @RequiresPermissions("drainMeterRow:list")
    @ResponseBody
    public AjaxResult errors(@RequestParam String batchNo) {
        return AjaxResult.successData(200, drainMeterRowService.listErrors(batchNo));
    }

    @Log(title = "温湿度断传判定", action = "outage")
    @ApiOperation(value = "查抄表当天远传是否断传", notes = "只认机房机组温度/湿度表计；true=断传可人工补，false=远传没断不许手补")
    @GetMapping("/outage")
    @RequiresPermissions("drainMeterRow:supplement")
    @ResponseBody
    public AjaxResult outage(@RequestParam String itemCode,
                             @RequestParam Date readTime) {
        if (MeterGaugeType.of(itemCode) == null) {
            return AjaxResult.error("只认机房机组温度/湿度表计，该计量点不走断传补录");
        }
        Map<String, Object> data = new HashMap<>();
        boolean outage = drainMeterRowService.isRemoteOutage(itemCode, readTime);
        data.put("itemCode", itemCode.trim());
        data.put("readTime", readTime);
        data.put("remoteOutage", outage);
        data.put("canSupplement", outage);
        return AjaxResult.successData(200, data);
    }

    @Log(title = "温湿度断传人工补录", action = "supplement")
    @ApiOperation(value = "断传人工补录", notes = "只在远传断传当天，值守员按实抄时间补机房机组温度/湿度读数；"
            + "远传没断的天不许手补；断传一天只许补一笔，抄表时刻照实填")
    @PostMapping("/supplement")
    @RequiresPermissions("drainMeterRow:supplement")
    @ResponseBody
    public AjaxResult supplement(@RequestParam String itemCode,
                                 @RequestParam BigDecimal qty,
                                 @RequestParam Date readTime) {
        if (MeterGaugeType.of(itemCode) == null) {
            return AjaxResult.error("只认机房机组温度/湿度表计，该计量点不走断传人工补录");
        }
        Long id = drainMeterRowService.supplementManual(itemCode, qty, readTime);
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        return AjaxResult.success(200, "断传读数已按抄表时间补录", data);
    }

    /**
     * 报文整体损坏（压根不是可解析的 JSON 等）：反序列化在进方法前就抛，
     * 局部接住回 JSON 错误，不让它落进全局处理跳 /error/999——那个模板不存在，会二次崩成裸500。
     * 只守本控制器，不碰全局处理器与其他链。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public AjaxResult handleUnreadable(HttpMessageNotReadableException e) {
        return AjaxResult.error("报文无法解析，整批未收（请核对是否为合法的明细行JSON数组）");
    }
}
