package com.yangpang.domain.claim;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 판매자 검수(M4 ①): 판매자배송 반품·교환은 회수 완료 후 판매자가 승인·거절, 거절은 고객 귀책만 */
class ClaimSellerDecisionTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private Claim claim(ClaimEnums.Reason reason, Long sellerId, ClaimEnums.Status status) {
        Claim c = Claim.create("C1", 1L, 1L, ClaimEnums.Type.RETURN, reason, null, ClaimEnums.PickupMethod.DOOR, 0, NOW);
        c.assignSeller(sellerId);
        c.changeStatus(status, NOW);
        return c;
    }

    @Test
    void 양팡이_처리하는_신청은_판매자_검수_대상이_아니다() {
        Claim c = claim(ClaimEnums.Reason.CHANGE_OF_MIND, null, ClaimEnums.Status.PICKED_UP);
        assertThat(c.awaitingSeller()).isFalse();
        assertThat(c.rejectable()).isFalse();
    }

    @Test
    void 회수가_끝나야_검수_대기가_된다() {
        assertThat(claim(ClaimEnums.Reason.CHANGE_OF_MIND, 7L, ClaimEnums.Status.PICKING_UP).awaitingSeller()).isFalse();
        assertThat(claim(ClaimEnums.Reason.CHANGE_OF_MIND, 7L, ClaimEnums.Status.PICKED_UP).awaitingSeller()).isTrue();
    }

    @Test
    void 고객_귀책만_거절할_수_있다() {
        assertThat(claim(ClaimEnums.Reason.CHANGE_OF_MIND, 7L, ClaimEnums.Status.PICKED_UP).rejectable()).isTrue();
        assertThat(claim(ClaimEnums.Reason.WRONG_OPTION, 7L, ClaimEnums.Status.PICKED_UP).rejectable()).isTrue();
        assertThat(claim(ClaimEnums.Reason.DEFECTIVE, 7L, ClaimEnums.Status.PICKED_UP).rejectable()).isFalse();
        assertThat(claim(ClaimEnums.Reason.WRONG_ITEM, 7L, ClaimEnums.Status.PICKED_UP).rejectable()).isFalse();
    }
}
