package com.yangpang.domain.coupon;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CashExpiryTest {

    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

    private static CashExpiry.Credit credit(long id, long amount, int daysFromNow) {
        return new CashExpiry.Credit(id, amount, NOW.plus(Duration.ofDays(daysFromNow)));
    }

    @Test
    void 쓴_캐시는_기간이_먼저_끝나는_적립분부터_차감된다() {
        // 1,000원(이미 만료) + 500원(10일 뒤 만료), 1,200원 사용 → 만료분 0원, 남은 300원은 10일 뒤 소멸 예정
        List<CashExpiry.Credit> credits = List.of(credit(2, 500, 10), credit(1, 1_000, -1));
        assertThat(CashExpiry.expiredAmount(credits, 1_200, NOW)).isZero();
        assertThat(CashExpiry.expiringWithin(credits, 1_200, NOW, Duration.ofDays(30))).isEqualTo(300);
    }

    @Test
    void 기간이_지난_남은_금액만_소멸() {
        List<CashExpiry.Credit> credits = List.of(credit(1, 1_000, -3), credit(2, 700, -1), credit(3, 2_000, 200));
        assertThat(CashExpiry.expiredAmount(credits, 400, NOW)).isEqualTo(1_300);
    }

    @Test
    void 소멸_기록도_쓴_금액으로_치므로_같은_적립분이_두_번_소멸되지_않는다() {
        List<CashExpiry.Credit> credits = List.of(credit(1, 1_000, -3), credit(2, 2_000, 200));
        long first = CashExpiry.expiredAmount(credits, 0, NOW);
        assertThat(first).isEqualTo(1_000);
        assertThat(CashExpiry.expiredAmount(credits, first, NOW)).isZero();
    }
}
