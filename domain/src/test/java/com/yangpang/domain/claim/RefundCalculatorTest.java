package com.yangpang.domain.claim;

import com.yangpang.domain.claim.RefundCalculator.ItemRefund;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefundCalculatorTest {

    @Test
    void 일부_상품만_취소하면_배송비는_돌려주지_않는다() {
        var r = RefundCalculator.calculate(List.of(new ItemRefund(1, 10_000)), 3_000, 0, false, 0);
        assertThat(r.total()).isEqualTo(10_000);
        assertThat(r.shippingRefund()).isZero();
    }

    @Test
    void 마지막_상품까지_취소하면_배송비도_환불() {
        var r = RefundCalculator.calculate(List.of(new ItemRefund(1, 10_000)), 3_000, 0, true, 0);
        assertThat(r.total()).isEqualTo(13_000);
    }

    @Test
    void 배송비는_두_번_환불하지_않는다() {
        var r = RefundCalculator.calculate(List.of(new ItemRefund(1, 10_000)), 3_000, 3_000, true, 0);
        assertThat(r.total()).isEqualTo(10_000);
    }

    @Test
    void 단순변심_반품비는_환불액에서_뺀다() {
        var r = RefundCalculator.calculate(List.of(new ItemRefund(1, 20_000)), 0, 0, false, 3_000);
        assertThat(r.returnFee()).isEqualTo(3_000);
        assertThat(r.total()).isEqualTo(17_000);
    }

    @Test
    void 반품비가_환불액보다_크면_0원() {
        var r = RefundCalculator.calculate(List.of(new ItemRefund(1, 2_000)), 0, 0, false, 3_000);
        assertThat(r.total()).isZero();
        assertThat(r.returnFee()).isEqualTo(2_000);
    }
}
