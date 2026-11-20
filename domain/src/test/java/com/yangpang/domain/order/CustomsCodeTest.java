package com.yangpang.domain.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomsCodeTest {

    @Test
    void 통관부호는_P와_숫자_12자리() {
        assertThat(OrderService.validCustomsCode(OrderService.normalizeCustomsCode("p1234-5678 9012"))).isTrue();
        assertThat(OrderService.validCustomsCode("P12345678901")).isFalse();
        assertThat(OrderService.validCustomsCode("A123456789012")).isFalse();
    }

    @Test
    void 가운데를_가린다() {
        assertThat(Order.maskCustomsCode("P123456789012")).isEqualTo("P12******9012");
    }
}
