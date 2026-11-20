package com.yangpang.domain.delivery;

import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.common.Region;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ArrivalEstimatorTest {

    /** 2026-11-19(목) 15:00 KST 로 시각을 고정 */
    private final ArrivalEstimator estimator = new ArrivalEstimator(
            Clock.fixed(Instant.parse("2026-11-19T06:00:00Z"), ZoneId.of("Asia/Seoul")));

    @Test
    void 양팡배송과_판매자양팡은_내일_도착_보장() {
        assertThat(estimator.label(DeliveryType.YANGPANG)).isEqualTo("내일(금) 도착 보장");
        assertThat(estimator.label(DeliveryType.FULFILLMENT)).isEqualTo("내일(금) 도착 보장");
    }

    @Test
    void 프레시는_새벽_도착() {
        assertThat(estimator.label(DeliveryType.FRESH)).isEqualTo("내일 새벽 도착 보장");
    }

    @Test
    void 판매자배송은_모레_도착_예정() {
        assertThat(estimator.label(DeliveryType.SELLER)).isEqualTo("모레(토) 도착 예정");
    }

    @Test
    void 직구는_날짜로_표시() {
        assertThat(estimator.label(DeliveryType.GLOBAL)).isEqualTo("11/23(월) 도착 예정");
    }

    @Test
    void 도서산간은_2일_더() {
        assertThat(estimator.arrivalDate(DeliveryType.YANGPANG, Region.REMOTE)).isEqualTo(LocalDate.of(2026, 11, 22));
    }

    @Test
    void 판매자배송이_일요일에_걸리면_월요일로() {
        // 목요일 + 2일 = 토요일, 도서산간 +2 = 월요일 (일요일 아님)
        // 금요일 주문 + 2일 = 일요일 → 월요일
        ArrivalEstimator friday = new ArrivalEstimator(
                Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneId.of("Asia/Seoul")));
        assertThat(friday.arrivalDate(DeliveryType.SELLER, Region.METRO)).isEqualTo(LocalDate.of(2026, 11, 23));
        // 양팡배송은 일요일에도 배송
        assertThat(new ArrivalEstimator(Clock.fixed(Instant.parse("2026-11-21T06:00:00Z"), ZoneId.of("Asia/Seoul")))
                .arrivalDate(DeliveryType.YANGPANG, Region.METRO)).isEqualTo(LocalDate.of(2026, 11, 22));
    }
}
