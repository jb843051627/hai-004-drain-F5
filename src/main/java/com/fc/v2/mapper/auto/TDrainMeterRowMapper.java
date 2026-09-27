package com.fc.v2.mapper.auto;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TDrainMeterRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 泵站远传计量明细行数据层
 * 对账结果一律走本表实笔：按批次查与按计量点查同一张表、同一 del_flag 口径，两头不打架。
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface TDrainMeterRowMapper extends BaseMapper<TDrainMeterRow> {

    String COLUMNS = "id, batch_no, row_no, item_code, qty, status, source, read_time, del_flag, "
            + "create_by, create_time, update_by, update_time, remark";

    /** 该批次全部在册行（对平/剔除都取），按报文行次升序；重复批次判定也靠它。 */
    @Select("SELECT " + COLUMNS + " FROM t_drain_meter_row "
            + "WHERE batch_no = #{batchNo} AND del_flag = 0 "
            + "ORDER BY row_no, id")
    List<TDrainMeterRow> listBatch(@Param("batchNo") String batchNo);

    /** 该批次的剔除行（status=2 已剔除），按报文行次升序，运行班照行次回找坏段。 */
    @Select("SELECT " + COLUMNS + " FROM t_drain_meter_row "
            + "WHERE batch_no = #{batchNo} AND status = 2 AND del_flag = 0 "
            + "ORDER BY row_no, id")
    List<TDrainMeterRow> listErrors(@Param("batchNo") String batchNo);

    /** 该计量点跨批次的全部在册行，按落库先后升序；与按批次查同表同口径。 */
    @Select("SELECT " + COLUMNS + " FROM t_drain_meter_row "
            + "WHERE item_code = #{itemCode} AND del_flag = 0 "
            + "ORDER BY create_time, id")
    List<TDrainMeterRow> listByItemCode(@Param("itemCode") String itemCode);

    /** 该计量点最近一笔已对平读数（status=1），即"上期读数"；无则返回 null。 */
    @Select("SELECT qty FROM t_drain_meter_row "
            + "WHERE item_code = #{itemCode} AND status = 1 AND del_flag = 0 "
            + "ORDER BY create_time DESC, id DESC LIMIT 1")
    BigDecimal lastAcceptedQty(@Param("itemCode") String itemCode);

    /**
     * 该表计在 [dayStart, dayEnd) 内有没有**已对平的远传**读数（source=0）。
     * 有=这一天远传没断，手工补录一律不许进；剔除行(status=2)不算数——
     * 那天报文被整行剔除等于没数，仍按断传认。
     * 远传报文未带抄表时刻时 read_time 为空，按落库时刻 create_time 归天。
     */
    @Select("SELECT COUNT(1) FROM t_drain_meter_row "
            + "WHERE item_code = #{itemCode} AND source = 0 AND status = 1 AND del_flag = 0 "
            + "AND COALESCE(read_time, create_time) >= #{dayStart} "
            + "AND COALESCE(read_time, create_time) < #{dayEnd}")
    int countRemoteAccepted(@Param("itemCode") String itemCode,
                            @Param("dayStart") String dayStart,
                            @Param("dayEnd") String dayEnd);

    /**
     * 该表计在 [dayStart, dayEnd) 内已落的**断传人工补录**笔（source=1, status=1）。
     * 断传一天只许补一笔，同一天再补（哪怕换个时刻）挡下，防重手补花。
     */
    @Select("SELECT " + COLUMNS + " FROM t_drain_meter_row "
            + "WHERE item_code = #{itemCode} AND source = 1 AND status = 1 AND del_flag = 0 "
            + "AND read_time >= #{dayStart} AND read_time < #{dayEnd} "
            + "ORDER BY read_time, id LIMIT 1")
    TDrainMeterRow findManualAccepted(@Param("itemCode") String itemCode,
                                      @Param("dayStart") String dayStart,
                                      @Param("dayEnd") String dayEnd);
}
