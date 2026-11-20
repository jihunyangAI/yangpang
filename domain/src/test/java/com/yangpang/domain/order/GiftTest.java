package com.yangpang.domain.order;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GiftTest {

    @Test
    void 결제_시점부터_5일_안에_배송지를_받아야_한다() {
        Instant t = Instant.parse("2026-11-20T00:00:00Z");
        Gift g = Gift.create(1L, "보내는분", "받는분", "010-1234-5678", null, t);
        assertThat(g.getExpiresAt()).isEqualTo(t.plus(Duration.ofDays(5)));
        g.restartWindow(t.plus(Duration.ofHours(2))); // 가상계좌 입금이 늦은 경우
        assertThat(g.getExpiresAt()).isEqualTo(t.plus(Duration.ofDays(5)).plus(Duration.ofHours(2)));
        assertThat(g.waiting()).isTrue();
    }

    @Test
    void 연락처는_가운데를_가린다() {
        Gift g = Gift.create(1L, "a", "b", "010-1234-5678", null, Instant.EPOCH);
        assertThat(g.maskedPhone()).isEqualTo("010-****-5678");
    }

    @Test
    void 링크_토큰은_20자_무작위() {
        String a = Gift.newToken(), b = Gift.newToken();
        assertThat(a).hasSize(20).isNotEqualTo(b).matches("[A-Za-z0-9]+");
    }

    @Test
    void 휴대폰_번호_정리() {
        assertThat(OrderService.normalizePhone("01012345678")).isEqualTo("010-1234-5678");
        assertThat(OrderService.normalizePhone("010-123-4567")).isEqualTo("010-123-4567");
    }
}
