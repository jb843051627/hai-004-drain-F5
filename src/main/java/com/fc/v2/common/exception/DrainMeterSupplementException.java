package com.fc.v2.common.exception;

/**
 * 机房机组温/湿度读数断传人工补录业务规则异常：
 * 非温湿度表计、远传未断当天却手工补、按补录时刻顶替抄表时刻、
 * 断传当天重复补、补未来时刻等红线一律抛此异常，由入口退回消息，不落成脏数据。
 *
 * @author fuce
 * @date 2026-09-26
 */
public class DrainMeterSupplementException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DrainMeterSupplementException(String message) {
        super(message);
    }
}
