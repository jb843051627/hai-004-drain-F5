package com.fc.v2.common.exception;

/**
 * 强排调度签批业务规则异常：顺序抢跑、会签未齐、重复落名、
 * 驳回后再签、批准后改动等红线一律抛此异常，由全局处理退回消息。
 *
 * @author fuce
 * @date 2026-09-24
 */
public class DrainDispatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DrainDispatchException(String message) {
        super(message);
    }
}
