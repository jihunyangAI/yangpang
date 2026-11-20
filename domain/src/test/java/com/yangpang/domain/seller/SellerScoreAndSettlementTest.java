package com.yangpang.domain.seller;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SellerScoreAndSettlementTest {

    @Test
    void 리뷰가_없으면_4점으로_보고_만점_근처() {
        // 평점 4.0 → 30점, 반품 없음 35, 문의 없음 25
        assertThat(SellerScoreService.total(0, 0, 0, 0, 0, 0)).isEqualTo(90.0);
    }

    @Test
    void 리뷰_몇개로는_크게_흔들리지_않는다() {
        // 1점 리뷰 1개: (1 + 20) / 6 = 3.5 → 25점
        assertThat(SellerScoreService.ratingPart(1, 1)).isEqualTo(25.0);
        // 5점 리뷰가 많으면 40점에 가까움
        assertThat(SellerScoreService.ratingPart(5 * 95, 95)).isCloseTo(39.5, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void 판매자_귀책_반품률_10퍼센트면_0점() {
        assertThat(SellerScoreService.claimPart(100, 10)).isZero();
        assertThat(SellerScoreService.claimPart(100, 5)).isEqualTo(17.5);
        assertThat(SellerScoreService.claimPart(3, 1)).isEqualTo(17.5); // 배송완료가 적으면 20개로 보고 계산 (1건에 0점이 되지 않게)
    }

    @Test
    void 문의_답변율() {
        assertThat(SellerScoreService.inquiryPart(4, 1)).isEqualTo(6.25);
        assertThat(SellerScoreService.inquiryPart(0, 0)).isEqualTo(25);
    }

    @Test
    void 등급() {
        assertThat(SellerScoreService.grade(85)).isEqualTo("우수");
        assertThat(SellerScoreService.grade(60)).isEqualTo("보통");
        assertThat(SellerScoreService.grade(40)).isEqualTo("주의");
        assertThat(SellerScoreService.grade(39.9)).isEqualTo("위험");
    }

    @Test
    void 수수료는_10퍼센트_물류대행은_15퍼센트_원단위_내림() {
        assertThat(SettlementService.commission(12_345, false)).isEqualTo(1_234);
        assertThat(SettlementService.commission(12_345, true)).isEqualTo(1_851);
    }

    @Test
    void 정산_주는_월요일부터() {
        assertThat(SettlementService.weekStart(LocalDate.of(2026, 11, 22))).isEqualTo(LocalDate.of(2026, 11, 16)); // 일요일
        assertThat(SettlementService.weekStart(LocalDate.of(2026, 11, 16))).isEqualTo(LocalDate.of(2026, 11, 16));
    }
}
