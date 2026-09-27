-- drain 汛期值守巡检自动派单种子（hai-004_a）
-- 配合「定时任务」里的 v2Task.dispatchPatrol()：系统照 t_drain_duty_shift 自己派。
-- 演示口径（基准日 2026-09-26）：
--   * 白班/夜班两条排班覆盖全天；任何落不进区间的时刻即空档，不派不吭声；
--   * 巡检 1001 到点 PS00 -> 派给当班人；1002 同站第二条 -> 一站不两人，后推；
--   * 1003 泵站编号缺失（坏行，跳过留账）；1004 指向停用站 PS01（坏行）；
--   * 1005 指向 PS02，该站压着在办修单 WX-OPEN-02 -> 本轮后推，修单销了下轮自然择上；
--   * 1006 未到点，不进候选。
-- 重复执行安全：一律 ON DUPLICATE KEY UPDATE / 先按主键幂等。

-- 增补一座在用泵站（PS00/PS01 已在 drain_seed.sql：PS00 在用、PS01 停用）
INSERT INTO t_drain_station (id, site_no, site_name, site_type, road_name, th1_max, th2_max, th3_max, status, del_flag, create_by, create_time)
VALUES
 (2, 'PS02', '河湾二号泵站', '雨水', '城北片区', 1.00, 5.00, 10.00, 0, 0, 'seed', NOW())
ON DUPLICATE KEY UPDATE site_name = VALUES(site_name), status = VALUES(status), del_flag = 0;

-- 值守排班：半开区间[start_at,end_at)，跨天夜班照实排
INSERT INTO t_drain_duty_shift (id, shift_name, start_at, end_at, duty_user, status, del_flag, create_by, create_time, remark)
VALUES
 (1, '白班', '2026-09-26 08:00:00', '2026-09-26 20:00:00', 'duty_li',   0, 0, 'seed', NOW(), '汛期二十四小时值守-白班'),
 (2, '夜班', '2026-09-26 20:00:00', '2026-09-27 08:00:00', 'duty_wang', 0, 0, 'seed', NOW(), '汛期二十四小时值守-夜班(跨天)')
ON DUPLICATE KEY UPDATE shift_name = VALUES(shift_name), start_at = VALUES(start_at),
 end_at = VALUES(end_at), duty_user = VALUES(duty_user), status = 0, del_flag = 0;

-- 压在 PS02 上没销的修单（在办 0）：该站巡检本轮后推
INSERT INTO t_drain_work_order (id, biz_no, site_no, stage, status, content, last_action, del_flag, create_by, create_time, remark)
VALUES
 (2001, 'WX-OPEN-02', 'PS02', 1, 0, '2026-09-25 10:00:00 推进至环节1：控制柜受潮待修', '巡检发现', 0, 'seed', NOW(), '未销修单，压着 PS02')
ON DUPLICATE KEY UPDATE biz_no = VALUES(biz_no), site_no = VALUES(site_no),
 status = VALUES(status), del_flag = 0;

-- 待派巡检条目（status=0）
INSERT INTO t_drain_patrol_task (id, item_no, site_no, due_at, amount, status, del_flag, create_by, create_time, remark)
VALUES
 (1001, 'XJ-20260926-01', 'PS00', '2026-09-26 20:00:00', 1.00, 0, 0, 'seed', NOW(), '到点该巡：正常派给当班值守员'),
 (1002, 'XJ-20260926-02', 'PS00', '2026-09-26 21:00:00', 1.00, 0, 0, 'seed', NOW(), '同站第二条：一站不两人，本轮后推'),
 (1003, 'XJ-20260926-03', NULL,   '2026-09-26 20:30:00', 1.00, 0, 0, 'seed', NOW(), '坏行：泵站编号缺失，跳过交人工核'),
 (1004, 'XJ-20260926-04', 'PS01', '2026-09-26 20:30:00', 1.00, 0, 0, 'seed', NOW(), '坏行：PS01 档案已停用'),
 (1005, 'XJ-20260926-05', 'PS02', '2026-09-26 20:30:00', 1.00, 0, 0, 'seed', NOW(), 'PS02 压着未销修单，本轮后推'),
 (1006, 'XJ-20260927-01', 'PS00', '2026-09-27 07:30:00', 1.00, 0, 0, 'seed', NOW(), '未到点：不进候选')
ON DUPLICATE KEY UPDATE item_no = VALUES(item_no), site_no = VALUES(site_no),
 due_at = VALUES(due_at), status = 0, del_flag = 0;

-- 定时任务入口：调度把任务喊起来，派不派/派给谁归服务层。
-- 禁止并发(concurrent=1)、状态正常(status=0)、misfire 策略=2，整点后每 10 分钟看一轮。
-- invoke_target 为无参方法：v2Task.dispatchPatrol()
INSERT INTO t_sys_quartz_job
 (id, job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
VALUES
 (332182389491109900, '汛期巡检自动派单', 'DRAIN', 'v2Task.dispatchPatrol()', '0 0/10 * * * ?', '2', '1', 0, 'seed', NOW(), '值守时段内照排班自动派巡检；空档不派；坏行跳过')
ON DUPLICATE KEY UPDATE job_name = VALUES(job_name), invoke_target = VALUES(invoke_target),
 cron_expression = VALUES(cron_expression), misfire_policy = VALUES(misfire_policy),
 concurrent = VALUES(concurrent), status = 0;
