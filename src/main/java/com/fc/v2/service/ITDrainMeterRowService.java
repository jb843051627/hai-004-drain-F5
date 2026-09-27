package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TDrainMeterRow;

/**
 * 泵站远传计量明细行 Service接口（batch-process 形状：整批提交，无单条增删改）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITDrainMeterRowService {

    /** 按主键回查单行 */
    TDrainMeterRow selectTDrainMeterRowById(Long id);

    /**
     * 整批提交：逐行校验，合法的入库、非法的记入失败明细（保留原始行号），返回**成功行数**。
     * 同一批次重复提交不得重复入库；行数超过单批上限返回 -1 且整批不入库；空批次返回 0。
     */
    int submitBatch(String batchNo, List<TDrainMeterRow> rows);

    /** 该批次的失败行明细（按行号升序） */
    List<TDrainMeterRow> listErrors(String batchNo);

    /**
     * 按批次查对账结果：收下与剔除的行都在，按报文行次升序。
     * 与 {@link #listByItemCode(String)} 同一张表、同一 del_flag 口径，两头查同一行结果一致。
     */
    List<TDrainMeterRow> listByBatch(String batchNo);

    /**
     * 按计量点查历史：该点跨批次的全部行，按落库先后升序。
     * 与 {@link #listByBatch(String)} 同一张表、同一 del_flag 口径，两头查同一行结果一致。
     */
    List<TDrainMeterRow> listByItemCode(String itemCode);

    /**
     * 机房机组温/湿度表计**断传人工补录**：值守员按实抄时刻把当天读数补进来。
     *
     * 两岔规矩都认：该计量点抄表当天已有已对平的远传读数（远传没断），手工补一律拒绝；
     * 只有远传断了（当天没有对平远传行）的日子才许补，且断传一天只许补一笔，
     * 抄表时刻照实填，不许拿补录时刻顶替。只认温度/湿度两类表计，其余计量点不走这条口子。
     *
     * @return 补录落库的明细行主键
     */
    Long supplementManual(String itemCode, BigDecimal qty, Date readTime);

    /** 重载：判断某表计某抄表时刻所在的天，远传是否已断（true=断了，可补；false=没断，不许手工补）。 */
    boolean isRemoteOutage(String itemCode, Date readTime);
}
