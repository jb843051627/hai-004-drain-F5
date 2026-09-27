-- drain 强排签批迁移种子（hai-004）
-- 上线初从旧办法迁进来的半截申请：前头批过几笔照实认几笔，不进新系统就清零。
-- 口径：单据 sign_count 不写死，由流水表实时重算；need_count 按节点名单口径
--      （或签=1，会签=指定名单人数3），迁移单若卡在会签节点且只认到2笔，则仍差1笔不过节点。
-- id=100：停在节点0（值班长，或签）且已有1笔批准 -> 迁移后视为节点0已过，卡在节点1待会签
-- id=101：停在节点1（排水科，会签3人），旧办法只签了2人 -> 照实认2笔，仍差1笔，不许替人凑数
INSERT INTO t_drain_dispatch_bill
 (id, bill_no, round_no, node_no, sign_mode, need_count, sign_count, pump_scale, start_time, status, del_flag, create_by, create_time, remark)
VALUES
 (100, 'QP-OLD-001', 1, 1, 1, 3, 0, 1200.00, '2026-09-10 02:00:00', 0, 0, 'migration', '2026-09-10 00:30:00', '旧办法迁移：值班长已批，待排水科会签'),
 (101, 'QP-OLD-002', 1, 1, 1, 3, 2, 800.00, '2026-09-11 03:00:00', 0, 0, 'migration', '2026-09-11 01:00:00', '旧办法迁移：排水科会签仅2人，差1人')
ON DUPLICATE KEY UPDATE bill_no = VALUES(bill_no), node_no = VALUES(node_no),
 sign_mode = VALUES(sign_mode), need_count = VALUES(need_count),
 pump_scale = VALUES(pump_scale), start_time = VALUES(start_time),
 status = VALUES(status), del_flag = 0;

-- 100 的旧痕：节点0值班长1笔（或签即过）
INSERT INTO t_drain_dispatch_sign
 (id, bill_id, bill_no, round_no, node_no, sign_mode, approver, action, comment, sign_time, seq_no, create_by, create_time)
VALUES
 (1001, 100, 'QP-OLD-001', 1, 0, 0, 'duty_lead01', 1, '水位逼近警戒线，同意上报', '2026-09-10 00:45:00', 1, 'migration', '2026-09-10 00:45:00')
ON DUPLICATE KEY UPDATE approver = VALUES(approver), action = VALUES(action), comment = VALUES(comment);

-- 101 的旧痕：节点0值班长1笔 + 节点1排水科2笔（会签差1人）
INSERT INTO t_drain_dispatch_sign
 (id, bill_id, bill_no, round_no, node_no, sign_mode, approver, action, comment, sign_time, seq_no, create_by, create_time)
VALUES
 (1011, 101, 'QP-OLD-002', 1, 0, 0, 'duty_lead01', 1, '同意强排', '2026-09-11 01:10:00', 1, 'migration', '2026-09-11 01:10:00'),
 (1012, 101, 'QP-OLD-002', 1, 1, 1, 'drain_ke_zhang', 1, '排水科同意', '2026-09-11 01:25:00', 2, 'migration', '2026-09-11 01:25:00'),
 (1013, 101, 'QP-OLD-002', 1, 1, 1, 'drain_ke_wang', 1, '同意', '2026-09-11 01:30:00', 3, 'migration', '2026-09-11 01:30:00')
ON DUPLICATE KEY UPDATE approver = VALUES(approver), action = VALUES(action), comment = VALUES(comment);
