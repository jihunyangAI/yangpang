package com.yangpang.domain.coupon;

import com.yangpang.domain.coupon.CouponSpec.DiscountType;
import com.yangpang.domain.coupon.CouponSpec.Kind;
import com.yangpang.domain.coupon.DiscountCalculator.Line;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountCalculatorTest {

    // 상품 1(카테고리 1>10>100) 20,000원 / 상품 2(카테고리 2>20>200) 10,000원
    private final Line a = new Line(0, 1, Set.of(1L, 10L, 100L), 20_000);
    private final Line b = new Line(1, 2, Set.of(2L, 20L, 200L), 10_000);

    private static CouponSpec product(long id, DiscountType t, long v, Long max, long min, Long category) {
        return new CouponSpec(id, "상품쿠폰" + id, Kind.PRODUCT, t, v, max, min, category, null);
    }

    private static CouponSpec cart(long id, DiscountType t, long v, Long max, long min, Long category) {
        return new CouponSpec(id, "장바구니쿠폰" + id, Kind.CART, t, v, max, min, category, null);
    }

    @Test
    void 정률_쿠폰은_최대_할인과_10원_단위_내림() {
        var c = product(1, DiscountType.RATE, 15, 2_000L, 0, null);
        assertThat(c.discountFor(9_990)).isEqualTo(1_490);   // 1,498.5 → 1,490
        assertThat(c.discountFor(20_000)).isEqualTo(2_000);  // 3,000 → 최대 2,000
    }

    @Test
    void 최소_주문금액_미달이면_0원() {
        assertThat(product(1, DiscountType.AMOUNT, 3_000, null, 30_000, null).discountFor(29_999)).isZero();
    }

    @Test
    void 정액_쿠폰은_상품_금액을_넘지_않는다() {
        assertThat(product(1, DiscountType.AMOUNT, 5_000, null, 0, null).discountFor(3_000)).isEqualTo(3_000);
    }

    @Test
    void 카테고리_쿠폰은_해당_카테고리_상품에만() {
        var c = product(1, DiscountType.AMOUNT, 1_000, null, 0, 10L);
        var r = DiscountCalculator.calculate(List.of(a, b), Map.of(0, c, 1, c), null, 0, 0);
        assertThat(r.line(0).productCouponDiscount()).isEqualTo(1_000);
        assertThat(r.line(1).productCouponDiscount()).isZero();
        assertThat(r.warnings()).isNotEmpty();
    }

    @Test
    void 같은_쿠폰을_두_상품에_쓸_수_없다() {
        var c = product(1, DiscountType.AMOUNT, 1_000, null, 0, null);
        var r = DiscountCalculator.calculate(List.of(a, b), Map.of(0, c, 1, c), null, 0, 0);
        assertThat(r.productCouponDiscount()).isEqualTo(1_000);
    }

    @Test
    void 장바구니_쿠폰은_상품쿠폰_적용_후_금액_비율로_안분() {
        var pc = product(1, DiscountType.AMOUNT, 2_000, null, 0, null);       // a: 20,000 → 18,000
        var cc = cart(2, DiscountType.RATE, 10, null, 0, null);               // (18,000 + 10,000) x 10% = 2,800
        var r = DiscountCalculator.calculate(List.of(a, b), Map.of(0, pc), cc, 0, 0);
        assertThat(r.cartCouponDiscount()).isEqualTo(2_800);
        assertThat(r.line(0).cartCouponDiscount()).isEqualTo(1_800);          // 2,800 x 18/28
        assertThat(r.line(1).cartCouponDiscount()).isEqualTo(1_000);
        assertThat(r.afterCoupons()).isEqualTo(30_000 - 2_000 - 2_800);
    }

    @Test
    void 안분_나머지는_가장_큰_상품에_몰아서_합계가_정확() {
        var three = List.of(new Line(0, 1, Set.of(), 10_000), new Line(1, 2, Set.of(), 10_000), new Line(2, 3, Set.of(), 10_000));
        var cc = cart(1, DiscountType.AMOUNT, 1_000, null, 0, null);          // 1,000 / 3 = 333.33
        var r = DiscountCalculator.calculate(three, Map.of(), cc, 0, 0);
        assertThat(r.lines().stream().mapToLong(DiscountCalculator.LineResult::cartCouponDiscount).sum()).isEqualTo(1_000);
    }

    @Test
    void 캐시는_보유액과_남은_금액을_넘지_않는다() {
        var r = DiscountCalculator.calculate(List.of(a, b), Map.of(), null, 50_000, 5_000);
        assertThat(r.cash()).isEqualTo(5_000);
        var r2 = DiscountCalculator.calculate(List.of(b), Map.of(), null, 50_000, 50_000);
        assertThat(r2.cash()).isEqualTo(10_000);
        assertThat(r2.line(1).payable()).isZero();
    }

    @Test
    void 캐시도_상품별로_안분된다() {
        var r = DiscountCalculator.calculate(List.of(a, b), Map.of(), null, 3_000, 10_000);
        assertThat(r.line(0).cash()).isEqualTo(2_000);
        assertThat(r.line(1).cash()).isEqualTo(1_000);
    }

    @Test
    void 가장_유리한_쿠폰_자동_선택() {
        var small = product(1, DiscountType.AMOUNT, 1_000, null, 0, null);
        var big = product(2, DiscountType.RATE, 20, null, 0, null);            // a에 쓰면 4,000, b에 쓰면 2,000
        var chosen = DiscountCalculator.bestProductCoupons(List.of(a, b), List.of(small, big));
        assertThat(chosen.get(0)).isEqualTo(big);
        assertThat(chosen.get(1)).isEqualTo(small);

        var c1 = cart(3, DiscountType.AMOUNT, 2_000, null, 0, null);
        var c2 = cart(4, DiscountType.RATE, 10, null, 50_000, null);           // 최소 주문금액 미달 → 0원
        assertThat(DiscountCalculator.bestCartCoupon(List.of(a, b), chosen, List.of(c1, c2))).isEqualTo(c1);
    }
}
