-- drain 抢险作业单五档流转种子（hai-004）
-- 演示口径（基准日 2026-09-26）：
--   * QX-DEMO-01 下穿隧道排水单：撤场办砸（警戒锥遗留被拍照上网）-> 撤回撤场档重办，
--     旧笔作废不进单面，重办新笔挂上，最终办结销号——全生命周期演示；
--   * QX-DEMO-02 在办单：走到处置档，正常在办；
--   * QX-DEMO-03 退回重办中：从处置档撤回到到场布防档，布防/处置两笔已作废，待重办；
--   * QX-DEMO-04 已销号单：五档走满，终态关死；
--   * QB-DEMO-05 QX-DEMO-04 的补单：母单一笔不动，补单从接单重走五档。
-- 重复执行安全：一律 ON DUPLICATE KEY UPDATE 按主键幂等。

INSERT INTO t_drain_rescue_order
 (id, biz_no, site_no, stage, status, supplement_of, del_flag, create_by, create_time, remark)
VALUES
 (3001, 'QX-DEMO-01', 'PS00', 4, 1, NULL, 0, 'seed', '2026-09-25 22:10:00', '下穿隧道排水：撤场办砸撤回重办后销号'),
 (3002, 'QX-DEMO-02', 'PS02', 2, 0, NULL, 0, 'seed', '2026-09-26 08:15:00', '在办单：处置档'),
 (3003, 'QX-DEMO-03', 'PS00', 1, 2, NULL, 0, 'seed', '2026-09-26 09:00:00', '退回重办中：布防范围不足撤回，待重办'),
 (3004, 'QX-DEMO-04', 'PS02', 4, 1, NULL, 0, 'seed', '2026-09-26 07:00:00', '已销号：五档走满'),
 (3005, 'QB-DEMO-05', 'PS02', 0, 0, 3004, 0, 'seed', '2026-09-26 14:00:00', '母单 QX-DEMO-04 的补单')
ON DUPLICATE KEY UPDATE biz_no = VALUES(biz_no), site_no = VALUES(site_no),
 stage = VALUES(stage), status = VALUES(status), supplement_of = VALUES(supplement_of), del_flag = 0;

-- QX-DEMO-01 的六笔流水：撤场第1回（已作废）+ 撤场第2回（重办，现行）
INSERT INTO t_drain_rescue_step
 (id, order_id, biz_no, stage, attempt_no, operator, action, detail, op_time, seq_no,
  void_flag, void_by, void_time, void_reason, create_by, create_time)
VALUES
 (30011, 3001, 'QX-DEMO-01', 0, 1, 'duty_li', 1, '接交警转警：下穿隧道积水，车辆缓行', '2026-09-25 22:10:00', 1, 0, NULL, NULL, NULL, 'seed', '2026-09-25 22:10:00'),
 (30012, 3001, 'QX-DEMO-01', 1, 1, 'crew_zhang', 1, '到场，警戒锥36个、围挡12块布设到位，封双向两车道', '2026-09-25 22:40:00', 2, 0, NULL, NULL, NULL, 'seed', '2026-09-25 22:40:00'),
 (30013, 3001, 'QX-DEMO-01', 2, 1, 'crew_zhang', 1, '两台潜水泵抽排，积水降至路面以下', '2026-09-26 01:30:00', 3, 0, NULL, NULL, NULL, 'seed', '2026-09-26 01:30:00'),
 (30014, 3001, 'QX-DEMO-01', 3, 1, 'crew_wang', 1, '人员装备清点撤离', '2026-09-26 02:10:00', 4, 1, 'duty_li', '2026-09-26 05:40:00', '撤场清点漏项：警戒锥遗留在路当中被拍照上网，撤回重办', 'seed', '2026-09-26 02:10:00'),
 (30015, 3001, 'QX-DEMO-01', 3, 2, 'crew_wang', 1, '二次到场清点，遗留警戒锥全部收回，路面恢复', '2026-09-26 06:20:00', 5, 0, NULL, NULL, NULL, 'seed', '2026-09-26 06:20:00'),
 (30016, 3001, 'QX-DEMO-01', 4, 1, 'duty_li', 1, '布防全部撤除确认，办结销号', '2026-09-26 06:50:00', 6, 0, NULL, NULL, NULL, 'seed', '2026-09-26 06:50:00')
ON DUPLICATE KEY UPDATE detail = VALUES(detail), void_flag = VALUES(void_flag),
 void_by = VALUES(void_by), void_time = VALUES(void_time), void_reason = VALUES(void_reason);

-- QX-DEMO-02 在办单：接单/布防/处置三笔现行，停在处置档
INSERT INTO t_drain_rescue_step
 (id, order_id, biz_no, stage, attempt_no, operator, action, detail, op_time, seq_no,
  void_flag, void_by, void_time, void_reason, create_by, create_time)
VALUES
 (30021, 3002, 'QX-DEMO-02', 0, 1, 'duty_wang', 1, '接群众来电：河湾路低洼段积水', '2026-09-26 08:15:00', 1, 0, NULL, NULL, NULL, 'seed', '2026-09-26 08:15:00'),
 (30022, 3002, 'QX-DEMO-02', 1, 1, 'crew_li', 1, '到场布防完成，过往车辆引导绕行', '2026-09-26 08:50:00', 2, 0, NULL, NULL, NULL, 'seed', '2026-09-26 08:50:00'),
 (30023, 3002, 'QX-DEMO-02', 2, 1, 'crew_li', 1, '移动泵车抽排中', '2026-09-26 10:20:00', 3, 0, NULL, NULL, NULL, 'seed', '2026-09-26 10:20:00')
ON DUPLICATE KEY UPDATE detail = VALUES(detail), void_flag = VALUES(void_flag),
 void_by = VALUES(void_by), void_time = VALUES(void_time), void_reason = VALUES(void_reason);

-- QX-DEMO-03 退回重办中：布防/处置两笔已作废（同一回撤回），停在到场布防档待重办
INSERT INTO t_drain_rescue_step
 (id, order_id, biz_no, stage, attempt_no, operator, action, detail, op_time, seq_no,
  void_flag, void_by, void_time, void_reason, create_by, create_time)
VALUES
 (30031, 3003, 'QX-DEMO-03', 0, 1, 'duty_wang', 1, '接巡查上报：临湖路口雨水篦子冒溢', '2026-09-26 09:00:00', 1, 0, NULL, NULL, NULL, 'seed', '2026-09-26 09:00:00'),
 (30032, 3003, 'QX-DEMO-03', 1, 1, 'crew_zhao', 1, '到场，主路布防完成', '2026-09-26 09:35:00', 2, 1, 'duty_wang', '2026-09-26 11:05:00', '布防范围不足：辅道未封，撤回重办', 'seed', '2026-09-26 09:35:00'),
 (30033, 3003, 'QX-DEMO-03', 2, 1, 'crew_zhao', 1, '清掏雨水篦子', '2026-09-26 10:40:00', 3, 1, 'duty_wang', '2026-09-26 11:05:00', '布防范围不足：辅道未封，撤回重办', 'seed', '2026-09-26 10:40:00')
ON DUPLICATE KEY UPDATE detail = VALUES(detail), void_flag = VALUES(void_flag),
 void_by = VALUES(void_by), void_time = VALUES(void_time), void_reason = VALUES(void_reason);

-- QX-DEMO-04 已销号：五笔现行走满
INSERT INTO t_drain_rescue_step
 (id, order_id, biz_no, stage, attempt_no, operator, action, detail, op_time, seq_no,
  void_flag, void_by, void_time, void_reason, create_by, create_time)
VALUES
 (30041, 3004, 'QX-DEMO-04', 0, 1, 'duty_li', 1, '接调度令：河湾二号泵站前池清障', '2026-09-26 07:00:00', 1, 0, NULL, NULL, NULL, 'seed', '2026-09-26 07:00:00'),
 (30042, 3004, 'QX-DEMO-04', 1, 1, 'crew_qian', 1, '到场布防完成', '2026-09-26 07:30:00', 2, 0, NULL, NULL, NULL, 'seed', '2026-09-26 07:30:00'),
 (30043, 3004, 'QX-DEMO-04', 2, 1, 'crew_qian', 1, '前池漂浮物清捞完毕', '2026-09-26 09:10:00', 3, 0, NULL, NULL, NULL, 'seed', '2026-09-26 09:10:00'),
 (30044, 3004, 'QX-DEMO-04', 3, 1, 'crew_qian', 1, '人员装备清点撤离，现场恢复', '2026-09-26 11:00:00', 4, 0, NULL, NULL, NULL, 'seed', '2026-09-26 11:00:00'),
 (30045, 3004, 'QX-DEMO-04', 4, 1, 'duty_li', 1, '复核通过，办结销号', '2026-09-26 11:30:00', 5, 0, NULL, NULL, NULL, 'seed', '2026-09-26 11:30:00')
ON DUPLICATE KEY UPDATE detail = VALUES(detail), void_flag = VALUES(void_flag),
 void_by = VALUES(void_by), void_time = VALUES(void_time), void_reason = VALUES(void_reason);

-- QB-DEMO-05 补单：只有接单一笔，母单 QX-DEMO-04 一笔不动
INSERT INTO t_drain_rescue_step
 (id, order_id, biz_no, stage, attempt_no, operator, action, detail, op_time, seq_no,
  void_flag, void_by, void_time, void_reason, create_by, create_time)
VALUES
 (30051, 3005, 'QB-DEMO-05', 0, 1, 'duty_li', 1, '补单：母单QX-DEMO-04撤场时刻填报有误，另开补单更正，母单不动', '2026-09-26 14:00:00', 1, 0, NULL, NULL, NULL, 'seed', '2026-09-26 14:00:00')
ON DUPLICATE KEY UPDATE detail = VALUES(detail), void_flag = VALUES(void_flag),
 void_by = VALUES(void_by), void_time = VALUES(void_time), void_reason = VALUES(void_reason);
