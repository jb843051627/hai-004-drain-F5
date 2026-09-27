package com.fc.v2.common.quartz.task;
import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TSysUser;
import com.fc.v2.model.custom.PatrolRoundResult;
import com.fc.v2.service.ITDrainPatrolTaskService;
import com.fc.v2.service.ITSysUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 *测试类
 * @CLASSNAME   V2Task
 * @Description 定时调度具体工作类
 * @Auther Jan  橙寂
 * @DATE 2019/9/2 0002 15:33
 */
@Component("v2Task")
public class V2Task {

	private static final Logger log = LoggerFactory.getLogger(V2Task.class);

	@Autowired
    private ITSysUserService userService;

	@Autowired
	private ITDrainPatrolTaskService drainPatrolTaskService;

    /**
     * 无参的任务
     */
    public void runTask1()
    {
        System.out.println("正在执行定时任务，无参方法");
    }

    /**
     * 有参任务
     * 目前仅执行常见的数据类型  Integer Long  带L  string  带 ''  bool Double 带 d
     * @param a
     * @param b
     */
    public void runTask2(Integer a,Long b,String c,Boolean d,Double e)
    {
    	List<TSysUser> list=  userService.selectTSysUserList(new QueryWrapper<TSysUser>());
    	System.err.println("用户查询num："+list.size());
        System.out.println("正在执行定时任务，带多个参数的方法"+a+"   "+b+" "+c+"  "+d+" "+e+"执行时间:"+DateUtil.now());
    }

	/**
	 * 汛期值守巡检自动派单（调度把任务喊起来，具体派不派、派给谁全归服务层算）。
	 * 无参，配在「定时任务」里：v2Task.dispatchPatrol()。
	 * 结果只认服务返回：空档一条不派是常事，整轮正常返回；坏行已在服务层跳过留账。
	 */
	public void dispatchPatrol() {
		PatrolRoundResult r = drainPatrolTaskService.dispatchRound();
		if (!r.isDutyPeriod()) {
			log.info("巡检派单：{} 处于排班空档，本轮不派", DateUtil.now());
			return;
		}
		log.info("巡检派单：{} 班次[{}]值守[{}] 候选{}条 派出{}条 压修单后推{}条 坏行跳过{}条",
				DateUtil.now(), r.getShiftName(), r.getDutyUser(), r.getCandidateCount(),
				r.getDispatchedCount(), r.getDeferredCount(), r.getBadRowCount());
	}
}
