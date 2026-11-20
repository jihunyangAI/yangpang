package com.yangpang.domain.coupon;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 양팡캐시 소멸 계산 (순수 함수).
 * 쓴 금액(사용·소멸)은 유효기간이 먼저 끝나는 적립분부터 차감한 것으로 본다(선입선출).
 * 그래서 남은 금액은 "유효기간이 늦은 적립분"에 남고, 기간이 지난 적립분에 남은 금액이 소멸 대상이다.
 */
public final class CashExpiry {

    private CashExpiry() {
    }

    /** 적립 한 건 (금액 > 0, 유효기간) */
    public record Credit(long id, long amount, Instant expiresAt) {
    }

    /** 적립분별 남은 금액 */
    public record Remaining(Credit credit, long remaining) {
    }

    /**
     * @param credits 적립분 (순서 무관)
     * @param spent   지금까지 쓴 금액 합계 (사용 + 이미 소멸된 금액, 양수)
     */
    public static List<Remaining> allocate(List<Credit> credits, long spent) {
        List<Credit> sorted = new ArrayList<>(credits);
        sorted.sort(Comparator.comparing(Credit::expiresAt).thenComparing(Credit::id));
        List<Remaining> result = new ArrayList<>();
        long left = spent;
        for (Credit c : sorted) {
            long used = Math.min(left, c.amount());
            left -= used;
            result.add(new Remaining(c, c.amount() - used));
        }
        return result;
    }

    /** 지금 소멸시켜야 할 금액 */
    public static long expiredAmount(List<Credit> credits, long spent, Instant now) {
        return allocate(credits, spent).stream().filter(r -> !r.credit().expiresAt().isAfter(now))
                .mapToLong(Remaining::remaining).sum();
    }

    /** 지금부터 within 안에 소멸될 금액 (안내용) */
    public static long expiringWithin(List<Credit> credits, long spent, Instant now, java.time.Duration within) {
        Instant until = now.plus(within);
        return allocate(credits, spent).stream()
                .filter(r -> r.credit().expiresAt().isAfter(now) && !r.credit().expiresAt().isAfter(until))
                .mapToLong(Remaining::remaining).sum();
    }
}
