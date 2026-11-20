package com.yangpang.domain.delivery;

import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.common.Region;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 도착 예정일 (기획서 8.3, MVP 버전).
 * - 양팡 물류: 자정 마감, 다음 날 도착 (매일 배송)
 * - 판매자배송: 2일 뒤, 직구: 4일 뒤 (일반 택배라 일요일 도착 없음 → 월요일로)
 * - 도서산간(REMOTE)은 +2일
 * 세부 권역·휴무일·오늘도착 마감(오전 10시)은 시뮬레이터 단계(M2)에서 확장한다.
 */
@Component
public class ArrivalEstimator {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Clock clock;

    public ArrivalEstimator(Clock clock) {
        this.clock = clock;
    }

    /** 오늘 주문하면 도착할 날짜 */
    public LocalDate arrivalDate(DeliveryType type, Region region) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        LocalDate date = switch (type) {
            case YANGPANG, FULFILLMENT, FRESH -> today.plusDays(1);
            case SELLER -> today.plusDays(2);
            case GLOBAL -> today.plusDays(4);
            case INSTALL -> today.plusDays(3);
        };
        if (region == Region.REMOTE) date = date.plusDays(2);
        if (!type.isYangpangLogistics() && date.getDayOfWeek() == DayOfWeek.SUNDAY) date = date.plusDays(1);
        return date;
    }

    /** 상품 카드·상세용 문구 (수도권 기준) */
    public String label(DeliveryType type) {
        LocalDate date = arrivalDate(type, Region.METRO);
        return switch (type) {
            case FRESH -> "내일 새벽 도착 보장";
            case YANGPANG, FULFILLMENT -> dayText(date) + " 도착 보장";
            case SELLER, GLOBAL -> dayText(date) + " 도착 예정";
            case INSTALL -> "설치일 지정 가능";
        };
    }

    /** 주문서용 문구 (배송지 권역 반영) */
    public String label(DeliveryType type, Region region) {
        if (region == Region.METRO) return label(type);
        return dayText(arrivalDate(type, region)) + " 도착 예정"; // 수도권 밖은 보장 대신 예정
    }

    /**
     * 주문 마감까지 남은 시간 문구 (양팡 물류는 자정 마감).
     * 예: "5시간 12분 내 주문 시". 판매자배송 등 마감이 없으면 null.
     */
    public String cutoffText(DeliveryType type) {
        if (!type.isYangpangLogistics()) return null;
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(KST));
        ZonedDateTime cutoff = now.toLocalDate().plusDays(1).atStartOfDay(KST);
        long minutes = Duration.between(now, cutoff).toMinutes();
        long h = minutes / 60, m = minutes % 60;
        return (h > 0 ? h + "시간 " : "") + m + "분 내 주문 시";
    }

    /** "내일(금)", "모레(토)", "11/23(월)" */
    public String dayText(LocalDate date) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        String dow = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
        long diff = date.toEpochDay() - today.toEpochDay();
        String prefix = diff == 0 ? "오늘" : diff == 1 ? "내일" : diff == 2 ? "모레" : date.getMonthValue() + "/" + date.getDayOfMonth();
        return prefix + "(" + dow + ")";
    }
}
