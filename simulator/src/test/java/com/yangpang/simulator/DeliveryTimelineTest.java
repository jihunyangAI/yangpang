package com.yangpang.simulator;

import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.order.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryTimelineTest {

    /** 2026-11-20 10:00 KST */
    private static final Instant T0 = Instant.parse("2026-11-20T01:00:00Z");
    private static final LocalDate TOMORROW = LocalDate.of(2026, 11, 21);

    private static DeliveryTimeline.Item item(long id, OrderStatus status, DeliveryType type) {
        return new DeliveryTimeline.Item(id, status, T0, type, TOMORROW, null);
    }

    @Test
    void 결제완료는_30분_뒤_상품준비중() {
        var i = item(1, OrderStatus.PAID, DeliveryType.YANGPANG);
        assertThat(DeliveryTimeline.isDue(i, T0.plus(Duration.ofMinutes(29)))).isFalse();
        assertThat(DeliveryTimeline.isDue(i, T0.plus(Duration.ofMinutes(30)))).isTrue();
    }

    @Test
    void 배송중은_도착_예정일_14시에_배송완료() {
        var i = item(1, OrderStatus.IN_TRANSIT, DeliveryType.YANGPANG);
        // 11/21 14:00 KST = 05:00Z
        assertThat(DeliveryTimeline.nextDueAt(i)).isEqualTo(Instant.parse("2026-11-21T05:00:00Z"));
    }

    @Test
    void 프레시는_새벽_6시에_배송완료() {
        var i = item(1, OrderStatus.IN_TRANSIT, DeliveryType.FRESH);
        assertThat(DeliveryTimeline.nextDueAt(i)).isEqualTo(Instant.parse("2026-11-20T21:00:00Z"));
    }

    @Test
    void 지연_대상은_하루_늦게_도착() {
        var i = item(7, OrderStatus.IN_TRANSIT, DeliveryType.YANGPANG);
        assertThat(DeliveryTimeline.isDelayed(7)).isTrue();
        assertThat(DeliveryTimeline.nextDueAt(i)).isEqualTo(Instant.parse("2026-11-22T05:00:00Z"));
    }

    @Test
    void 도착예정일이_이미_지났어도_배송중이_된_지_1시간은_지나야_배송완료() {
        var i = new DeliveryTimeline.Item(1, OrderStatus.IN_TRANSIT, T0, DeliveryType.YANGPANG,
                LocalDate.of(2026, 11, 10), null);
        assertThat(DeliveryTimeline.nextDueAt(i)).isEqualTo(T0.plus(Duration.ofHours(1)));
    }

    @Test
    void 배송완료_7일_뒤_자동_구매확정() {
        Instant delivered = Instant.parse("2026-11-21T05:00:00Z");
        var i = new DeliveryTimeline.Item(1, OrderStatus.DELIVERED, delivered, DeliveryType.YANGPANG, TOMORROW, delivered);
        assertThat(DeliveryTimeline.nextDueAt(i)).isEqualTo(delivered.plus(Duration.ofDays(7)));
    }

    @Test
    void 구매확정_취소는_더_진행하지_않는다() {
        assertThat(DeliveryTimeline.nextDueAt(item(1, OrderStatus.CONFIRMED, DeliveryType.YANGPANG))).isNull();
        assertThat(DeliveryTimeline.nextDueAt(item(1, OrderStatus.CANCELED, DeliveryType.YANGPANG))).isNull();
    }
}
