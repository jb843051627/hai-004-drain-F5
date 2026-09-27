package com.fc.v2.common.exception;

/**
 * 抢险作业单流转业务规则异常：跨档抢挪、同一档挪第二回、销号后再动、
 * 撤回不重办就往下溜、作废记录与新记录并排挂单、母单上直接改补等红线一律抛此异常，
 * 由全局处理退回消息。
 *
 * @author fuce
 * @date 2026-09-27
 */
public class DrainRescueException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DrainRescueException(String message) {
        super(message);
    }
}
