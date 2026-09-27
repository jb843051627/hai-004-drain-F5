package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.fc.v2.mapper.auto.TDrainMeterBatchMapper;
import com.fc.v2.mapper.auto.TDrainMeterRowMapper;
import com.fc.v2.model.auto.TDrainMeterBatch;
import com.fc.v2.model.auto.TDrainMeterRow;

/**
 * 远传计量成批汇交红线测试（内存库模拟明细表）：
 * 逐行对账、剔除留行次原由、负读数与倒挂拦截、同批次幂等、批内同点并行、
 * 超上限整批不收、空批不算数、按批次与按计量点两头口径一致。
 *
 * @author fuce
 * @date 2026-09-25
 */
class TDrainMeterRowServiceImplTest {

    private TDrainMeterRowServiceImpl service;
    private final Map<Long, TDrainMeterRow> store = new LinkedHashMap<>();
    private final Map<String, TDrainMeterBatch> heads = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    @BeforeEach
    void setUp() {
        store.clear();
        heads.clear();
        seq.set(0);
        TDrainMeterRowMapper mapper = mock(TDrainMeterRowMapper.class);
        TDrainMeterBatchMapper batchMapper = mock(TDrainMeterBatchMapper.class);

        when(mapper.insert(any())).thenAnswer(inv -> {
            TDrainMeterRow r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(seq.incrementAndGet());
            }
            store.put(r.getId(), r);
            return 1;
        });
        when(mapper.listBatch(anyString())).thenAnswer(inv -> {
            String batchNo = inv.getArgument(0);
            return store.values().stream()
                    .filter(r -> batchNo.equals(r.getBatchNo()) && Integer.valueOf(0).equals(r.getDelFlag()))
                    .sorted(Comparator.comparing(TDrainMeterRow::getRowNo,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(TDrainMeterRow::getId))
                    .collect(Collectors.toList());
        });
        when(mapper.listErrors(anyString())).thenAnswer(inv -> {
            String batchNo = inv.getArgument(0);
            return store.values().stream()
                    .filter(r -> batchNo.equals(r.getBatchNo()) && Integer.valueOf(2).equals(r.getStatus())
                            && Integer.valueOf(0).equals(r.getDelFlag()))
                    .sorted(Comparator.comparing(TDrainMeterRow::getRowNo,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(TDrainMeterRow::getId))
                    .collect(Collectors.toList());
        });
        when(mapper.listByItemCode(anyString())).thenAnswer(inv -> {
            String item = inv.getArgument(0);
            return store.values().stream()
                    .filter(r -> item.equals(r.getItemCode()) && Integer.valueOf(0).equals(r.getDelFlag()))
                    .sorted(Comparator.comparing(TDrainMeterRow::getId))
                    .collect(Collectors.toList());
        });
        when(mapper.lastAcceptedQty(anyString())).thenAnswer(inv -> {
            String item = inv.getArgument(0);
            TDrainMeterRow latest = null;
            for (TDrainMeterRow r : store.values()) {
                if (item.equals(r.getItemCode()) && Integer.valueOf(1).equals(r.getStatus())
                        && Integer.valueOf(0).equals(r.getDelFlag())
                        && (latest == null || r.getId().longValue() > latest.getId().longValue())) {
                    latest = r;
                }
            }
            return latest == null ? null : latest.getQty();
        });

        // 批次头幂等闸门：同 batch_no 头一份 insert 落库，再交撞唯一键；
        // selectForUpdate 把已写实的头一笔计数取回来
        when(batchMapper.insert(any(TDrainMeterBatch.class))).thenAnswer(inv -> {
            TDrainMeterBatch h = inv.getArgument(0);
            if (heads.containsKey(h.getBatchNo())) {
                throw new DuplicateKeyException("uk_batch_no");
            }
            heads.put(h.getBatchNo(), h);
            return 1;
        });
        when(batchMapper.selectForUpdate(anyString())).thenAnswer(inv -> heads.get(inv.getArgument(0)));

        service = new TDrainMeterRowServiceImpl();
        ReflectionTestUtils.setField(service, "drainMeterRowMapper", mapper);
        ReflectionTestUtils.setField(service, "drainMeterBatchMapper", batchMapper);
    }

    private TDrainMeterRow row(Integer rowNo, String item, String qty) {
        TDrainMeterRow r = new TDrainMeterRow();
        r.setRowNo(rowNo);
        r.setItemCode(item);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    private List<TDrainMeterRow> validRows(String itemPrefix, int n) {
        List<TDrainMeterRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(row(i + 1, itemPrefix + i, "1"));
        }
        return rows;
    }

    // 1. 全对得平：整批收下，行次、批次号照实落库
    @Test
    void allValid_acceptedAndCounted() {
        List<TDrainMeterRow> rows = Arrays.asList(
                row(1, "PS00-EM01", "100.00"),
                row(2, "PS00-EM02", "200.00"),
                row(3, "PS01-EM01", "50.50"));
        int ok = service.submitBatch("B20260925-01", rows);

        assertEquals(3, ok, "三行全对平，收下三行");
        assertEquals(3, store.size());
        for (TDrainMeterRow r : store.values()) {
            assertEquals(1, r.getStatus().intValue(), "收下的行状态=已对平");
            assertEquals("B20260925-01", r.getBatchNo(), "批次号按汇交口径统一落");
            assertEquals(0, r.getDelFlag().intValue());
        }
    }

    // 2. 对不平的剔除：留报文行次与原由；对得平的照样先收下
    @Test
    void invalidRows_rejectedWithRowNoAndReason() {
        List<TDrainMeterRow> rows = Arrays.asList(
                row(1, "PS00-EM01", "10"),
                row(2, "PS00-EM02", "-3"),
                row(3, "  ", "5"),
                row(4, "PS00-EM03", null));
        int ok = service.submitBatch("B20260925-02", rows);

        assertEquals(1, ok, "只有第1行对得平");
        List<TDrainMeterRow> errors = service.listErrors("B20260925-02");
        assertEquals(3, errors.size(), "三行不平，逐笔留痕");
        assertEquals(2, errors.get(0).getRowNo().intValue(), "按报文行次升序");
        assertTrue(errors.get(0).getRemark().contains("负"), "负读数要写明原由");
        assertEquals(3, errors.get(1).getRowNo().intValue());
        assertTrue(errors.get(1).getRemark().contains("计量点编码"));
        assertEquals(4, errors.get(2).getRowNo().intValue());
        assertTrue(errors.get(2).getRemark().contains("读数缺失"));
        for (TDrainMeterRow e : errors) {
            assertEquals(2, e.getStatus().intValue(), "剔除行状态=已剔除");
            assertEquals("B20260925-02", e.getBatchNo(), "剔除行也挂本批次，回头查得到");
        }
    }

    // 3. 本期比上期还小先拦下；持平、走高放行；被剔除的行不顶上期
    @Test
    void currentBelowPrevious_rejected() {
        assertEquals(1, service.submitBatch("D1", Arrays.asList(row(1, "A", "100"))));

        assertEquals(0, service.submitBatch("D2", Arrays.asList(row(1, "A", "90"))),
                "90<上期100，整行拦下");
        List<TDrainMeterRow> errors = service.listErrors("D2");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getRemark().contains("上期"), "原由要点出上期读数");

        assertEquals(1, service.submitBatch("D3", Arrays.asList(row(1, "A", "100"))),
                "与上期持平算平，放行");
        assertEquals(1, service.submitBatch("D4", Arrays.asList(row(1, "A", "150"))),
                "高于上期正常走字，放行");
        assertEquals(1, service.submitBatch("D5", Arrays.asList(row(1, "B", "10"))),
                "别的计量点不受A的上期管");

        // 被剔除的行不算数：-5没落库，后面50没有上期可对，照收
        assertEquals(0, service.submitBatch("E1", Arrays.asList(row(1, "X", "-5"))));
        assertEquals(1, service.submitBatch("E2", Arrays.asList(row(1, "X", "50"))),
                "上期只认已对平的实笔，剔除行不顶上期");
    }

    // 4. 空批不算提交成功，一行都不落
    @Test
    void emptyBatch_returnsZeroAndStoresNothing() {
        assertEquals(0, service.submitBatch("EMPTY1", new ArrayList<>()));
        assertEquals(0, service.submitBatch("EMPTY2", null));
        assertTrue(store.isEmpty(), "空批一行都不许落库");
    }

    // 5. 超单批上限：整批不收，直说-1；500行整好照收
    @Test
    void overLimit_wholeBatchRefused() {
        assertEquals(-1, service.submitBatch("L1", validRows("I", 501)));
        assertTrue(store.isEmpty(), "超上限整批不收，一行都不许漏进去");
        assertEquals(500, service.submitBatch("L2", validRows("I", 500)),
                "500行顶格照收");
        assertEquals(500, store.size());
    }

    // 6. 同一批次号交两遍：库里只留头一遍，按先落库的那份回话
    @Test
    void duplicateBatch_firstStands() {
        List<TDrainMeterRow> first = Arrays.asList(
                row(1, "A", "10"), row(2, "B", "-1"), row(3, "C", "30"));
        assertEquals(2, service.submitBatch("DUP", first));
        int sizeAfterFirst = store.size();

        int again = service.submitBatch("DUP", Arrays.asList(
                row(1, "Z", "999"), row(2, "Y", "888")));
        assertEquals(2, again, "重复提交按先落库的那份回话，话数一致");
        assertEquals(sizeAfterFirst, store.size(), "第二遍一行都不许多落");
        List<TDrainMeterRow> batch = service.listByBatch("DUP");
        assertEquals(3, batch.size(), "库里只有头一遍的三行");
        assertTrue(batch.stream().noneMatch(r -> "Z".equals(r.getItemCode())
                || "Y".equals(r.getItemCode())), "第二遍的内容进不来");
    }

    // 7. 批内同计量点（同统计期）两行并作一行：以先到的为准，行次留备注
    @Test
    void sameItemMergedIntoOneRow_firstWins() {
        List<TDrainMeterRow> rows = Arrays.asList(
                row(1, "A", "100"),
                row(2, "A", "105"),
                row(3, "B", "7"));
        int ok = service.submitBatch("M1", rows);

        assertEquals(2, ok, "A的两行并作一行，加B共两行");
        assertEquals(2, store.size());
        List<TDrainMeterRow> aRows = service.listByItemCode("A");
        assertEquals(1, aRows.size(), "计量点A只落一行");
        assertEquals(new BigDecimal("100"), aRows.get(0).getQty(), "以先到的行为准");
        assertTrue(aRows.get(0).getRemark().contains("行次2")
                && aRows.get(0).getRemark().contains("并入"), "被并的行次留在备注里");
    }

    // 8. 报文没给行次的按批内顺序补：剔除行也带着行次，运行班找得到坏段
    @Test
    void missingRowNo_backfilledByPosition() {
        List<TDrainMeterRow> rows = Arrays.asList(
                row(null, "A", "1"),
                row(null, "B", "-2"),
                row(9, "C", "3"));
        service.submitBatch("N1", rows);

        List<TDrainMeterRow> batch = service.listByBatch("N1");
        assertEquals(1, batch.get(0).getRowNo().intValue(), "没给行次按批内顺序补");
        assertEquals(2, batch.get(1).getRowNo().intValue());
        assertEquals(2, batch.get(1).getStatus().intValue(), "第2行负数被剔，行次照补");
        assertEquals(9, batch.get(2).getRowNo().intValue(), "报文给了行次就留报文的");
        assertEquals(2, service.listErrors("N1").get(0).getRowNo().intValue(),
                "剔除行带着行次，回头找得着坏段");
    }

    // 9. 空行（报文里混进的空内容）也记账：剔除并留行次
    @Test
    void nullLine_recordedAsErrorWithPosition() {
        List<TDrainMeterRow> rows = new ArrayList<>();
        rows.add(row(1, "A", "1"));
        rows.add(null);
        int ok = service.submitBatch("N2", rows);

        assertEquals(1, ok);
        List<TDrainMeterRow> errors = service.listErrors("N2");
        assertEquals(1, errors.size());
        assertEquals(2, errors.get(0).getRowNo().intValue(), "空行按批内位置留行次");
        assertTrue(errors.get(0).getRemark().contains("空"));
    }

    // 10. 批次号缺失：落不了库，视同未提交
    @Test
    void blankBatchNo_returnsZero() {
        assertEquals(0, service.submitBatch("  ", Arrays.asList(row(1, "A", "1"))));
        assertTrue(store.isEmpty());
    }

    // 11. 按批次查与按计量点查，两头口径一致
    @Test
    void queryByBatchAndItem_consistent() {
        service.submitBatch("Q1", Arrays.asList(row(1, "A", "10"), row(2, "B", "-1")));
        service.submitBatch("Q2", Arrays.asList(row(1, "A", "20"), row(2, "B", "5")));

        List<TDrainMeterRow> q1 = service.listByBatch("Q1");
        assertEquals(2, q1.size(), "批次的收下与剔除都在");
        assertEquals(1, q1.get(0).getRowNo().intValue(), "按报文行次升序");
        assertEquals(2, q1.get(1).getRowNo().intValue());

        List<TDrainMeterRow> itemA = service.listByItemCode("A");
        assertEquals(2, itemA.size(), "A跨两个批次两行");
        assertEquals(new BigDecimal("10"), itemA.get(0).getQty(), "按落库先后升序");
        assertEquals(new BigDecimal("20"), itemA.get(1).getQty());

        // 同一行从两头查，状态、读数、原由一字不差
        List<TDrainMeterRow> itemB = service.listByItemCode("B");
        TDrainMeterRow bFromItem = itemB.get(0);
        TDrainMeterRow bFromBatch = q1.stream()
                .filter(r -> r.getId().equals(bFromItem.getId())).findFirst().get();
        assertEquals(bFromBatch.getStatus(), bFromItem.getStatus());
        assertEquals(bFromBatch.getQty(), bFromItem.getQty());
        assertEquals(bFromBatch.getRemark(), bFromItem.getRemark());
        assertEquals(2, bFromItem.getStatus().intValue(), "B在Q1被剔，两头都是已剔除");
        assertEquals(1, itemB.get(1).getStatus().intValue(), "B在Q2对平，两头都是已对平");

        List<TDrainMeterRow> errors = service.listErrors("Q1");
        assertEquals(1, errors.size());
        assertEquals(2, errors.get(0).getRowNo().intValue());
    }

    // 12. 并发重交（头头已认领）：输家撞唯一键后照头一笔计数回话，一行不多落
    @Test
    void concurrentDuplicate_loserEchoesFirstHead() {
        // 模拟同批已被并发请求抢先把头落进来（本次调用一认领就撞唯一键）
        TDrainMeterBatch head = new TDrainMeterBatch();
        head.setBatchNo("CC");
        head.setTotalCount(2);
        head.setOkCount(2);
        head.setFailCount(0);
        heads.put("CC", head);

        int echoed = service.submitBatch("CC", Arrays.asList(row(1, "A", "1"), row(2, "B", "2")));
        assertEquals(2, echoed, "输家按头一笔的 ok_count 回话");
        assertTrue(store.isEmpty(), "输家一行明细都不许落，数量不翻倍");
    }

    // 13. 报文里某行坏值（控制器已转成 parseError）：只剔该行，好行照收，不整批连坐
    @Test
    void parseErrorRow_onlyThatRowRejected() {
        TDrainMeterRow broken = new TDrainMeterRow();
        broken.setRowNo(2);
        broken.setItemCode("B");
        broken.setParseError("报文行字段无法识别：读数不是数字");
        int ok = service.submitBatch("P1", Arrays.asList(row(1, "A", "100"), broken, row(3, "C", "7")));

        assertEquals(2, ok, "坏值只剔第2行，另外两行好行照收");
        List<TDrainMeterRow> errors = service.listErrors("P1");
        assertEquals(1, errors.size());
        assertEquals(2, errors.get(0).getRowNo().intValue());
        assertTrue(errors.get(0).getRemark().contains("无法识别"));
    }

    // 14. 编码超长、备注超长：本行剔除/截断，不拖垮整批落库
    @Test
    void overlongFields_doNotBreakWholeBatch() {
        StringBuilder longItem = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            longItem.append('X');
        }
        TDrainMeterRow longCode = row(1, longItem.toString(), "1");
        TDrainMeterRow withLongRemark = row(2, "OK", "2");
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            note.append('备');
        }
        withLongRemark.setRemark(note.toString());

        int ok = service.submitBatch("O1", Arrays.asList(longCode, withLongRemark));
        assertEquals(1, ok, "超长编码剔第1行，第2行照收");
        TDrainMeterRow kept = service.listByBatch("O1").stream()
                .filter(r -> Integer.valueOf(1).equals(r.getStatus())).findFirst().get();
        assertEquals(500, kept.getRemark().length(), "备注截到列长500以内，插入不炸库");
    }

    // 15. 批次号带空格：落库与查询同一 trim 口径，回话对得上行
    @Test
    void batchNoTrimmed_consistentAcrossQueries() {
        service.submitBatch("  T1  ", Arrays.asList(row(1, "A", "1"), row(2, "B", "-1")));

        assertEquals(2, service.listByBatch("T1").size());
        assertEquals(2, service.listByBatch("  T1  ").size());
        assertEquals(1, service.listErrors("T1").size());
        assertEquals(1, service.listErrors(" T1 ").size());
    }
}
