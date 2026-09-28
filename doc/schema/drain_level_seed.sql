-- drain 泵站起排判线迁移种子（hai-004）
-- 判线是起不起排的正式依据：代号一样的几版按有效期管，半开区间 [eff_start, eff_end)，
-- 交接那一瞬间老版已失效、新版刚生效；几版同刻叠着照 priority(大的优先) 取；
-- status=1 已作废的老线任何时刻都不许拿来填，回判历史撞上它只回「当下无可用判线」。
-- 基准日 2026-09-27：今天城东走湿季版(4002)；汛期加密版(4003) 已在 09-01 收口。
-- 重复执行安全：ON DUPLICATE KEY UPDATE 按主键幂等。

-- LS-CX-001 城东雨水泵站判线：干季版/湿季版在 2026-05-01 00:00:00 交接（该刻归湿季版）
INSERT INTO t_drain_level_std
 (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag, create_by, create_time, remark)
VALUES
 (4001, 'LS-CX-001', '城东雨水判线(干季版)', 2.00, 5.00, 8.00, '2025-10-01 00:00:00', '2026-05-01 00:00:00', 10, 0, 0, 'migration', '2025-09-20 10:00:00', '干季版：交接时刻 2026-05-01 00:00:00 起失效(不含)'),
 (4002, 'LS-CX-001', '城东雨水判线(湿季版)', 1.50, 3.50, 6.00, '2026-05-01 00:00:00', '2026-10-01 00:00:00', 10, 0, 0, 'migration', '2026-04-25 10:00:00', '湿季版：交接那一刻即生效；2026-09-27 当下走这版'),
 (4003, 'LS-CX-001', '城东雨水判线(汛期加密版)', 1.00, 3.00, 5.00, '2026-06-01 00:00:00', '2026-09-01 00:00:00', 20, 0, 0, 'migration', '2026-05-28 10:00:00', '与湿季版同刻叠着，priority=20 压过湿季版；09-01 收口后自动回湿季版'),
 (4004, 'LS-CX-001', '城东雨水判线(老办法版)', 3.00, 6.00, 9.00, '2024-01-01 00:00:00', '2025-10-01 00:00:00', 1, 1, 0, 'migration', '2023-12-25 10:00:00', '已作废：回判 2025 年夏季即便撞上它的老窗口也不许拿它填'),
 (4005, 'LS-HL-001', '合流老站判线(存量两档)', 1.50, 4.50, NULL, '2026-01-01 00:00:00', NULL, 5, 0, 0, 'migration', '2026-01-01 00:00:00', '早年只录到前两档：三档 NULL=未划线，高出二档归超档，存量线照旧能用')
ON DUPLICATE KEY UPDATE rule_code = VALUES(rule_code), rule_name = VALUES(rule_name),
 th1_max = VALUES(th1_max), th2_max = VALUES(th2_max), th3_max = VALUES(th3_max),
 eff_start = VALUES(eff_start), eff_end = VALUES(eff_end), priority = VALUES(priority),
 status = VALUES(status), del_flag = 0;
