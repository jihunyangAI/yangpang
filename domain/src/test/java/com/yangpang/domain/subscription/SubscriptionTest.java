package com.yangpang.domain.subscription;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.order.MockPayment;
import com.yangpang.domain.order.OrderService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private Subscription sub(int weeks, LocalDate next) {
        return Subscription.create(1L, 10L, 100L, 1, weeks, 5L, MockPayment.Method.YANGPAY, next, Instant.EPOCH);
    }

    @Test
    void 배송_예정일_이틀_전부터_주문() {
        Subscription s = sub(2, TODAY.plusDays(3));
        assertThat(s.due(TODAY)).isFalse();
        assertThat(s.due(TODAY.plusDays(1))).isTrue();
        assertThat(s.nextOrderDate()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    void 회차_주문_후_실제_도착일부터_주기만큼_뒤가_다음_배송() {
        Subscription s = sub(2, TODAY);
        s.ordered(TODAY.plusDays(1));
        assertThat(s.getRounds()).isEqualTo(1);
        assertThat(s.getNextDeliveryDate()).isEqualTo(TODAY.plusDays(15));
        assertThat(s.getLastNote()).isNull();
    }

    @Test
    void 건너뛰면_한_주기_밀린다() {
        Subscription s = sub(4, TODAY.plusDays(5));
        s.skip("재고 부족");
        assertThat(s.getNextDeliveryDate()).isEqualTo(TODAY.plusDays(33));
        assertThat(s.getSkipped()).isEqualTo(1);
        assertThat(s.getLastNote()).isEqualTo("재고 부족");
    }

    @Test
    void 주기_변경은_마지막_배송일_기준_주문일이_지나지_않게() {
        Subscription s = sub(4, TODAY);
        s.ordered(TODAY.minusDays(10)); // 10일 전 도착
        s.changeCycle(1, TODAY);        // 마지막 배송 + 1주 = 3일 전 → 너무 이르니 오늘 + 3일
        assertThat(s.getNextDeliveryDate()).isEqualTo(TODAY.plusDays(3));
        s.changeCycle(2, TODAY);        // 마지막 배송 + 2주 = 4일 뒤
        assertThat(s.getNextDeliveryDate()).isEqualTo(TODAY.plusDays(4));
    }

    @Test
    void 주기_수량_결제수단_검사() {
        assertThatThrownBy(() -> Subscription.validateCycle(3)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Subscription.validateQuantity(11)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Subscription.validateMethod(MockPayment.Method.BANK)).isInstanceOf(BusinessException.class);
        Subscription.validateCycle(4);
        Subscription.validateMethod(MockPayment.Method.CARD);
    }

    @Test
    void 정기배송_할인은_10원_단위_내림() {
        assertThat(OrderService.subscriptionDiscount(12_990, 5)).isEqualTo(640);  // 649.5 → 640
        assertThat(OrderService.subscriptionDiscount(10_000, 5)).isEqualTo(500);
        assertThat(OrderService.subscriptionDiscount(10_000, 0)).isZero();
    }
}
