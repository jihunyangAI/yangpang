package com.yangpang.domain.policy;

/**
 * 관리자가 바꿀 수 있는 운영 기준값 (V27 policy_setting). 기본값은 DB에 행이 없을 때만 쓴다.
 */
public enum Policy {
    SHIPPING_FREE_THRESHOLD(19_800),
    SHIPPING_FEE(3_000),
    GLOBAL_SHIPPING_FEE(3_000),
    GLOBAL_RETURN_FEE(15_000),
    RETURN_WINDOW_DAYS(7),
    PLUS_RETURN_WINDOW_DAYS(30),
    PAYMENT_TIMEOUT_MINUTES(10),
    GIFT_ACCEPT_DAYS(5),
    PURCHASE_CASH_PERCENT(1),
    DELAY_COMPENSATION_CASH(1_000),
    PLUS_MONTHLY_FEE(7_890),
    SUBSCRIPTION_DISCOUNT_PERCENT(5),
    PARTNERS_RATE_PERCENT(3),
    SELLER_COMMISSION_PERCENT(10),
    FULFILLMENT_FEE_PERCENT(5),
    SETTLEMENT_PAY_AFTER_DAYS(7),
    SELLER_WARNING_SCORE(40),
    ATTENDANCE_DAILY_CASH(30),
    ATTENDANCE_STREAK_BONUS(300),
    INVITE_REWARD_CASH(3_000),
    INVITE_MONTHLY_LIMIT(10);

    private final long defaultValue;

    Policy(long defaultValue) {
        this.defaultValue = defaultValue;
    }

    public long defaultValue() {
        return defaultValue;
    }
}
