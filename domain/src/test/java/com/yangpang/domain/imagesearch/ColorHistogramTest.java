package com.yangpang.domain.imagesearch;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;

class ColorHistogramTest {

    private static float[] h(String name) {
        return ColorHistogram.of(ColorHistogram.swatch(ColorNames.of(name).orElseThrow()));
    }

    @Test
    void 비율의_합은_1() {
        float sum = 0;
        for (float v : h("레드")) sum += v;
        assertThat(sum).isCloseTo(1f, org.assertj.core.data.Offset.offset(0.001f));
    }

    @Test
    void 무채색과_유채색을_나눈다() {
        assertThat(ColorHistogram.bin(0x111111)).isZero();                     // 검정
        assertThat(ColorHistogram.bin(0xFAFAFA)).isEqualTo(ColorHistogram.WHITE_BIN);
        assertThat(ColorHistogram.bin(0xD32F2F)).isGreaterThanOrEqualTo(ColorHistogram.GRAY_BINS);
        assertThat(ColorHistogram.bin(0xD32F2F)).isNotEqualTo(ColorHistogram.bin(0x2F6FD8)); // 빨강 ≠ 파랑
    }

    @Test
    void 같은_색끼리_가장_비슷하다() {
        float[] red = h("레드");
        assertThat(ColorHistogram.similarity(red, red)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(ColorHistogram.similarity(red, h("레드"))).isGreaterThan(ColorHistogram.similarity(red, h("블루")));
        assertThat(ColorHistogram.similarity(h("블랙"), h("블랙"))).isGreaterThan(ColorHistogram.similarity(h("블랙"), h("핑크")));
    }

    @Test
    void 흰_배경은_덜_센다() {
        // 흰 바탕만 같은 두 그림(빨강·파랑)의 유사도는 낮아야 한다
        assertThat(ColorHistogram.similarity(h("레드"), h("블루"))).isLessThan(0.5);
    }

    @Test
    void 가까운_색_이름() {
        assertThat(ColorNames.nearest(new Color(20, 30, 90))).isEqualTo("네이비");
        assertThat(ColorNames.nearest(new Color(250, 250, 250))).isEqualTo("화이트");
    }
}
