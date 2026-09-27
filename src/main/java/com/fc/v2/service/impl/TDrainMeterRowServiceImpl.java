package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fc.v2.common.exception.DrainMeterSupplementException;
import com.fc.v2.mapper.auto.TDrainMeterBatchMapper;
import com.fc.v2.mapper.auto.TDrainMeterRowMapper;
import com.fc.v2.model.auto.TDrainMeterBatch;
import com.fc.v2.model.auto.TDrainMeterRow;
import com.fc.v2.model.custom.MeterGaugeType;
import com.fc.v2.service.ITDrainMeterRowService;

/**
 * 泵站远传计量明细行 Service业务层处理（batch-process 形状：整批提交）
 *
 * 逐行着过的口径：
 * 1. 对得平的收下（已对平），对不平的剔除（已剔除）——剔除行必须留报文行次与原由，
 *    行次报文给了就留报文的，没给按批内顺序补，运行班回头照行次找坏段；
 *    一行坏只剔它自己，其余好行照样落库，绝不整批连坐；
 * 2. 读数为负、本期比上期还小的行一律拦下，不许进库（上期=该计量点最近一笔已对平读数）；
 * 3. 统计期随批次走（一批一个统计期），批内同计量点编码即同统计期，两行并作一行，以先到的为准；
 * 4. 同一批次号交两遍，库里只留头一遍——批次头表 t_drain_meter_batch 以 batch_no 为主键
 *    做幂等闸门，并发双击/重试只有头一份插得进，另一份按头一笔计数回话，一行不多落；
 * 5. 行数超上限整批不收；一行都没有的空批不算提交成功。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TDrainMeterRowServiceImpl implements ITDrainMeterRowService {

    /** 一次汇交行数上限，超了整批不收 */
    private static final int MAX_ROWS = 500;
    /** 行状态 1已对平 2已剔除 */
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    /** 读数来源 0远传 1断传人工补录 */
    private static final int SOURCE_REMOTE = 0;
    private static final int SOURCE_MANUAL = 1;

    /** 计量点编码列长 varchar(64)，超长按本行不平处理，不拖垮整批 */
    private static final int ITEM_MAX_LEN = 64;
    /** 备注列长 varchar(500)，落库前截到列长以内 */
    private static final int REMARK_MAX_LEN = 500;
    /** 读数 decimal(12,2) 整数位上限 10 位，即 9999999999.99 */
    private static final int QTY_MAX_INT_DIGITS = 10;
    private static final int QTY_MAX_SCALE = 2;

    /** 幂等闸门落库后对方还看不到头一笔（头一笔事务回滚）的极端情形，最多再认领一次 */
    private static final int CLAIM_MAX_ATTEMPTS = 2;

    @javax.annotation.Resource
    private TDrainMeterRowMapper drainMeterRowMapper;

    @javax.annotation.Resource
    private TDrainMeterBatchMapper drainMeterBatchMapper;

    @Override
    public TDrainMeterRow selectTDrainMeterRowById(Long id) {
        return this.drainMeterRowMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitBatch(String batchNo, List<TDrainMeterRow> rows) {
        // 空批：一行都没有，不算提交成功，批次头也不许落
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        // 超上限：整批不收，直说
        if (rows.size() > MAX_ROWS) {
            return -1;
        }
        String no = batchNo == null ? "" : batchNo.trim();
        if (no.isEmpty()) {
            // 批次号是重批判定的唯一凭据，没有批次号落不了库，视同未提交
            return 0;
        }

        // 先在内存里把整批逐行算定：这一步不碰库，任何一行脏都只脏它自己，
        // 不会在落库途中抛异常把同一事务里的好行连坐回滚。
        List<TDrainMeterRow> toInsert = new ArrayList<>();
        Map<String, TDrainMeterRow> keptByItem = new HashMap<>();
        Map<String, BigDecimal> prevCache = new HashMap<>();
        int ok = 0;
        int pos = 0;
        for (TDrainMeterRow r : rows) {
            pos++;
            if (r == null) {
                toInsert.add(buildRow(no, Integer.valueOf(pos), null, null, STATUS_FAIL, "报文行为空"));
                continue;
            }
            // 这行在报文反序列化阶段就无法识别（如读数不是数字）：只剔它自己，不连累同批好行
            if (r.getParseError() != null) {
                toInsert.add(buildRow(no,
                        r.getRowNo() != null ? r.getRowNo() : Integer.valueOf(pos),
                        null, null, STATUS_FAIL, r.getParseError()));
                continue;
            }
            // 行次：报文给的留报文的，没给的按批内顺序补——剔除行没有行次，回头找不着坏段
            Integer rowNo = r.getRowNo() != null ? r.getRowNo() : Integer.valueOf(pos);
            String item = r.getItemCode() == null ? "" : r.getItemCode().trim();
            BigDecimal qty = r.getQty();

            String reason = rejectReason(item, qty, prevCache);
            if (reason != null) {
                toInsert.add(buildRow(no, rowNo, item.isEmpty() ? null : item, qty, STATUS_FAIL, reason));
                continue;
            }
            TDrainMeterRow kept = keptByItem.get(item);
            if (kept != null) {
                // 批内同计量点（同统计期）两行并作一行：以先到的为准，被并的行次留在备注里
                appendMergeNote(kept, rowNo);
                continue;
            }
            TDrainMeterRow okRow = buildRow(no, rowNo, item, qty, STATUS_OK, r.getRemark());
            // 远传行只认批次报文，不采信报文里的抄表时刻：read_time 留空，归天按落库时间算，
            // 也避开 uk_item_source_read 把同刻重报的合法远传行误挡（NULL 不参与唯一冲突）。
            // 断传人工补录才必须按实抄时刻填 read_time（走 supplementManual 另一条口子）。
            okRow.setSource(SOURCE_REMOTE);
            keptByItem.put(item, okRow);
            toInsert.add(okRow);
            ok++;
        }
        int fail = toInsert.size() - ok;

        // 幂等闸门：batch_no 是批次头主键，并发重交只有一份插得进。
        // 输家的唯一键冲突在本事务内被 catch（MySQL 只回滚这一条语句），
        // 再用当前读把赢家已提交的头一笔计数取回来回话。
        TDrainMeterBatch head = null;
        for (int attempt = 0; attempt < CLAIM_MAX_ATTEMPTS; attempt++) {
            TDrainMeterBatch claim = new TDrainMeterBatch();
            claim.setBatchNo(no);
            claim.setTotalCount(rows.size());
            claim.setOkCount(0);
            claim.setFailCount(0);
            try {
                this.drainMeterBatchMapper.insert(claim);
                head = claim;
                break;
            } catch (DuplicateKeyException dup) {
                TDrainMeterBatch first = this.drainMeterBatchMapper.selectForUpdate(no);
                if (first != null) {
                    // 同一批次交两遍：按先落库那份的明细与计数回话
                    return first.getOkCount() == null ? 0 : first.getOkCount().intValue();
                }
                // 赢家事务回滚把行带走了：本行退回复认一次，仍认领不上才算冲突
            }
        }
        if (head == null) {
            throw new RuntimeException("批次[" + no + "]并发认领冲突，请整批重交");
        }

        // 全部算定后一次落库：明细字段在上面已按列长/量程清洗，正常报文不会在插入时炸库
        for (TDrainMeterRow row : toInsert) {
            this.drainMeterRowMapper.insert(row);
        }
        // 头一笔计数写实，后来重交的就照这个数回话
        head.setOkCount(ok);
        head.setFailCount(fail);
        this.drainMeterBatchMapper.updateById(head);
        return ok;
    }

    @Override
    public List<TDrainMeterRow> listErrors(String batchNo) {
        return this.drainMeterRowMapper.listErrors(normalizeBatchNo(batchNo));
    }

    @Override
    public List<TDrainMeterRow> listByBatch(String batchNo) {
        return this.drainMeterRowMapper.listBatch(normalizeBatchNo(batchNo));
    }

    @Override
    public List<TDrainMeterRow> listByItemCode(String itemCode) {
        return itemCode == null ? new ArrayList<>()
                : this.drainMeterRowMapper.listByItemCode(itemCode.trim());
    }

    private String normalizeBatchNo(String batchNo) {
        return batchNo == null ? "" : batchNo.trim();
    }

    /** 不平的原由；对得平返回 null。 */
    private String rejectReason(String item, BigDecimal qty, Map<String, BigDecimal> prevCache) {
        if (item.isEmpty()) {
            return "计量点编码缺失";
        }
        if (item.length() > ITEM_MAX_LEN) {
            return "计量点编码超长(列长" + ITEM_MAX_LEN + ")";
        }
        if (qty == null) {
            return "读数缺失";
        }
        if (qty.compareTo(BigDecimal.ZERO) < 0) {
            return "读数为负";
        }
        if (qty.scale() > QTY_MAX_SCALE || qty.precision() - qty.scale() > QTY_MAX_INT_DIGITS) {
            return "读数量程越界(decimal(12,2))";
        }
        BigDecimal prev = previousQty(item, prevCache);
        if (prev != null && qty.compareTo(prev) < 0) {
            return "本期读数小于上期读数(上期" + prev + ")";
        }
        return null;
    }

    /** 上期读数：该计量点最近一笔已对平读数；批内同点只查一次。 */
    private BigDecimal previousQty(String itemCode, Map<String, BigDecimal> prevCache) {
        if (prevCache.containsKey(itemCode)) {
            return prevCache.get(itemCode);
        }
        BigDecimal prev = this.drainMeterRowMapper.lastAcceptedQty(itemCode);
        prevCache.put(itemCode, prev);
        return prev;
    }

    /** 被并行的行次追加到留下那行的备注里，运行班照行次还能找回原报文。 */
    private void appendMergeNote(TDrainMeterRow kept, Integer mergedRowNo) {
        String tag = "同批行次" + mergedRowNo + "同计量点并入本行";
        String note = kept.getRemark();
        kept.setRemark(clampRemark(note == null || note.isEmpty() ? tag : note + "；" + tag));
    }

    /** 备注截到 varchar(500) 列长以内，保住原报文备注头部，不叫一条备注拖垮整行插入。 */
    private String clampRemark(String remark) {
        if (remark == null) {
            return null;
        }
        return remark.length() > REMARK_MAX_LEN ? remark.substring(0, REMARK_MAX_LEN) : remark;
    }

    private TDrainMeterRow buildRow(String batchNo, Integer rowNo, String itemCode,
                                    BigDecimal qty, int status, String remark) {
        TDrainMeterRow row = new TDrainMeterRow();
        row.setBatchNo(batchNo);
        row.setRowNo(rowNo);
        row.setItemCode(itemCode);
        row.setQty(qty);
        row.setStatus(Integer.valueOf(status));
        row.setSource(SOURCE_REMOTE);
        row.setDelFlag(Integer.valueOf(0));
        row.setRemark(clampRemark(remark));
        return row;
    }

    @Override
    public boolean isRemoteOutage(String itemCode, Date readTime) {
        String item = itemCode == null ? "" : itemCode.trim();
        if (item.isEmpty() || readTime == null || MeterGaugeType.of(item) == null) {
            // 非温湿度表计或时刻缺失，不属于断传补录口径：按"不可补"回话
            return false;
        }
        String[] day = dayBounds(readTime);
        return this.drainMeterRowMapper.countRemoteAccepted(item, day[0], day[1]) == 0;
    }

    /**
     * 机房机组温/湿度断传人工补录。两岔规矩：
     * 远传没断的天（当天已有对平远传行）不许手工补；断了的天才许补，且一天一笔、照实抄时刻补。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long supplementManual(String itemCode, BigDecimal qty, Date readTime) {
        String item = itemCode == null ? "" : itemCode.trim();
        if (item.isEmpty()) {
            throw new DrainMeterSupplementException("计量点编码缺失，无法补录");
        }
        MeterGaugeType gauge = MeterGaugeType.of(item);
        if (gauge == null) {
            // 只有机房机组温度、湿度两个表计走断传补录，电度等其余计量点不许从这条口子进
            throw new DrainMeterSupplementException("计量点[" + item + "]不是机房机组温度/湿度表计，不走断传人工补录");
        }
        if (readTime == null) {
            throw new DrainMeterSupplementException("补录必须按实际抄表时间填写，不许拿补录时刻顶替");
        }
        if (readTime.after(new Date())) {
            throw new DrainMeterSupplementException("抄表时间不能晚于当前时刻，不许预补");
        }
        if (qty == null) {
            throw new DrainMeterSupplementException("读数缺失，无法补录");
        }
        if (qty.compareTo(BigDecimal.ZERO) < 0) {
            throw new DrainMeterSupplementException("读数为负，无法补录");
        }
        if (qty.scale() > QTY_MAX_SCALE || qty.precision() - qty.scale() > QTY_MAX_INT_DIGITS) {
            throw new DrainMeterSupplementException("读数量程越界(decimal(12,2))");
        }

        String[] day = dayBounds(readTime);
        // 岔规矩一：当天有对平远传行=远传没断，手工补一律不许进
        if (this.drainMeterRowMapper.countRemoteAccepted(item, day[0], day[1]) > 0) {
            throw new DrainMeterSupplementException(
                    gauge.getLabel() + "表计[" + item + "]在" + day[0].substring(0, 10)
                            + "远传未断，当天读数由远传走，不许手工补");
        }
        // 断传一天只许补一笔，重手再补（哪怕换个时刻）挡下
        TDrainMeterRow existed = this.drainMeterRowMapper.findManualAccepted(item, day[0], day[1]);
        if (existed != null) {
            throw new DrainMeterSupplementException(
                    gauge.getLabel() + "表计[" + item + "]在" + day[0].substring(0, 10)
                            + "已人工补录一笔，断传一天只许补一次");
        }

        TDrainMeterRow row = new TDrainMeterRow();
        row.setItemCode(item);
        row.setQty(qty);
        row.setStatus(STATUS_OK);
        row.setSource(SOURCE_MANUAL);
        row.setReadTime(readTime);
        row.setDelFlag(0);
        row.setRemark(clampRemark("断传人工补录(" + gauge.getLabel() + ")"));
        try {
            this.drainMeterRowMapper.insert(row);
        } catch (DuplicateKeyException dup) {
            // 同表计同抄表时刻并发补两笔：库唯一键兜底，第二笔拦下
            throw new DrainMeterSupplementException(
                    gauge.getLabel() + "表计[" + item + "]该抄表时刻已补录，请勿重复补");
        }
        return row.getId();
    }

    /** 抄表时刻所在自然日的半开区间 [当日00:00:00, 次日00:00:00)，墙钟字符串，避时区坑。 */
    private static String[] dayBounds(Date readTime) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(readTime);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String start = fmt.format(cal.getTime());
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
        String end = fmt.format(cal.getTime());
        return new String[] {start, end};
    }
}
