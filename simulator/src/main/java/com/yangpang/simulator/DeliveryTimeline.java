package com.yangpang.simulator;

import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.order.OrderStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 배송 진행 타임라인 (순수 계산). "이 상품은 언제 다음 단계로 넘어가야 하나"를 정한다.
 *
 * <pre>
 * 결제완료 --30분--> 상품준비중 --2시간--> 출고완료 --3시간--> 배송중
 *   --(도착 예정일 14시, 프레시는 06시)--> 배송완료 --7일--> 구매확정(자동)
 * </pre>
 * 상품 20개 중 1개꼴(id % 20 == 7)은 하루 늦게 도착한다 (지연 시뮬레이션).
 */
public final class DeliveryTimeline {

    private DeliveryTimeline() {
    }

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final Duration PAID_TO_PREPARING = Duration.ofMinutes(30);
    static final Duration PREPARING_TO_SHIPPED = Duration.ofHours(2);
    static final Duration SHIPPED_TO_IN_TRANSIT = Duration.ofHours(3);
    static final Duration MIN_IN_TRANSIT = Duration.ofHours(1);
    static final Duration AUTO_CONFIRM_AFTER = Duration.ofDays(7);

    /** 계산에 필요한 주문 상품 정보 */
    public record Item(long id, OrderStatus status, Instant statusChangedAt, DeliveryType deliveryType,
                       LocalDate promisedDate, Instant deliveredAt) {
    }

    /** 다음 단계로 넘어갈 시각 (진행할 수 없는 상태면 null) */
    public static Instant nextDueAt(Item item) {
        return switch (item.status()) {
            case PAID -> item.statusChangedAt().plus(PAID_TO_PREPARING);
            case PREPARING -> item.statusChangedAt().plus(PREPARING_TO_SHIPPED);
            case SHIPPED -> item.statusChangedAt().plus(SHIPPED_TO_IN_TRANSIT);
            case IN_TRANSIT -> later(item.statusChangedAt().plus(MIN_IN_TRANSIT), deliveryTime(item));
            case DELIVERED -> (item.deliveredAt() == null ? item.statusChangedAt() : item.deliveredAt()).plus(AUTO_CONFIRM_AFTER);
            default -> null;
        };
    }

    public static boolean isDue(Item item, Instant now) {
        Instant due = nextDueAt(item);
        return due != null && !now.isBefore(due);
    }

    public static boolean isDelayed(long itemId) {
        return itemId % 20 == 7;
    }

    /** 도착 예정일의 배송 시각 (지연 대상이면 하루 뒤) */
    static Instant deliveryTime(Item item) {
        LocalDate date = item.promisedDate() == null
                ? item.statusChangedAt().atZone(KST).toLocalDate().plusDays(1)
                : item.promisedDate();
        if (isDelayed(item.id()) && item.deliveryType() != DeliveryType.INSTALL) date = date.plusDays(1); // 설치 약속일은 지키는 것으로
        LocalTime time = item.deliveryType() == DeliveryType.FRESH ? LocalTime.of(6, 0) : LocalTime.of(14, 0);
        return date.atTime(time).atZone(KST).toInstant();
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
