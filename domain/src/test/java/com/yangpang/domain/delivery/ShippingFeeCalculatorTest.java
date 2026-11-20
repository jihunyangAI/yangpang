package com.yangpang.domain.delivery;

import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.delivery.ShippingFeeCalculator.Line;
import com.yangpang.domain.delivery.ShippingFeeCalculator.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.yangpang.domain.delivery.ShippingFeeCalculator.YANGPANG_GROUP;
import static org.assertj.core.api.Assertions.assertThat;

class ShippingFeeCalculatorTest {

    private static Line yangpang(long amount) {
        return new Line(DeliveryType.YANGPANG, 1, "양팡", 3000, null, amount);
    }

    private static Line seller(long sellerId, long fee, Long threshold, long amount) {
        return new Line(DeliveryType.SELLER, sellerId, "판매자" + sellerId, fee, threshold, amount);
    }

    @Test
    void 일반회원_양팡배송_19800원_미만이면_배송비_3000원() {
        Result r = ShippingFeeCalculator.calculate(List.of(yangpang(18_000)), false);
        assertThat(r.totalFee()).isEqualTo(3_000);
        assertThat(r.group(YANGPANG_GROUP).remainingForFree()).isEqualTo(1_800);
    }

    @Test
    void 일반회원_양팡배송_정확히_19800원이면_무료() {
        Result r = ShippingFeeCalculator.calculate(List.of(yangpang(19_800)), false);
        assertThat(r.totalFee()).isZero();
        assertThat(r.group(YANGPANG_GROUP).remainingForFree()).isZero();
    }

    @Test
    void 기획서_시나리오2_2000원_더_담으면_무료_쿠폰으로_기준_아래가_되면_다시_유료() {
        // 18,000원 → 배송비
        assertThat(ShippingFeeCalculator.calculate(List.of(yangpang(18_000)), false).totalFee()).isEqualTo(3_000);
        // 2,000원 추가 → 20,000원 무료
        assertThat(ShippingFeeCalculator.calculate(List.of(yangpang(18_000), yangpang(2_000)), false).totalFee()).isZero();
        // 쿠폰 1,000원 할인 → 최종 19,000원 → 다시 배송비 (할인 적용 후 금액 기준)
        assertThat(ShippingFeeCalculator.calculate(List.of(yangpang(18_000), yangpang(1_000)), false).totalFee()).isEqualTo(3_000);
    }

    @Test
    void 양팡배송_프레시_판매자양팡은_한_묶음으로_합산() {
        List<Line> lines = List.of(
                yangpang(8_000),
                new Line(DeliveryType.FRESH, 1, "양팡", 3000, null, 6_000),
                new Line(DeliveryType.FULFILLMENT, 2, "한결상사", 3000, null, 6_000));
        Result r = ShippingFeeCalculator.calculate(lines, false);
        assertThat(r.groups()).hasSize(1);
        assertThat(r.totalFee()).isZero(); // 8,000 + 6,000 + 6,000 = 20,000
    }

    @Test
    void 플러스회원은_양팡물류_금액과_상관없이_무료() {
        Result r = ShippingFeeCalculator.calculate(List.of(yangpang(1_000)), true);
        assertThat(r.totalFee()).isZero();
    }

    @Test
    void 판매자배송은_판매자별로_따로_계산() {
        List<Line> lines = List.of(
                seller(4, 3_000, 30_000L, 31_000),  // 조건부 무료 충족
                seller(5, 2_500, null, 50_000),     // 기준 없음 → 항상 유료
                yangpang(5_000));                   // 양팡 물류 미달
        Result r = ShippingFeeCalculator.calculate(lines, false);
        assertThat(r.groups()).extracting(ShippingFeeCalculator.Group::key)
                .containsExactly(YANGPANG_GROUP, "SELLER-4", "SELLER-5");
        assertThat(r.totalFee()).isEqualTo(3_000 + 0 + 2_500);
        assertThat(r.group("SELLER-5").remainingForFree()).isZero();
    }

    @Test
    void 판매자_조건부무료_기준까지_남은_금액() {
        Result r = ShippingFeeCalculator.calculate(List.of(seller(4, 3_000, 30_000L, 25_000)), false);
        assertThat(r.group("SELLER-4").remainingForFree()).isEqualTo(5_000);
        assertThat(r.totalFee()).isEqualTo(3_000);
    }

    @Test
    void 빈_목록이면_배송비_0원() {
        assertThat(ShippingFeeCalculator.calculate(List.of(), false).totalFee()).isZero();
    }

    @Test
    void 양팡직구는_양팡배송과_따로_묶고_같은_기준() {
        Line global = new Line(DeliveryType.GLOBAL, 7, "해외판매자", 5000, null, 15_000);
        Result r = ShippingFeeCalculator.calculate(List.of(yangpang(25_000), global), false);
        assertThat(r.groups()).extracting(ShippingFeeCalculator.Group::key).containsExactly(YANGPANG_GROUP, ShippingFeeCalculator.GLOBAL_GROUP);
        assertThat(r.group(ShippingFeeCalculator.GLOBAL_GROUP).fee()).isEqualTo(3_000); // 판매자 배송비 아님
        assertThat(r.group(ShippingFeeCalculator.GLOBAL_GROUP).remainingForFree()).isEqualTo(4_800);
        assertThat(ShippingFeeCalculator.calculate(List.of(global), true).totalFee()).isZero(); // 플러스 무료
    }
}
