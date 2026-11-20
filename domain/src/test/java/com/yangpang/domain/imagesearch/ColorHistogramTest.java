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
    void 이웃한_색상도_비슷하게_본다() {
        // 초록(색상 약 110°)으로 찾을 때 연두빛 상추(약 78°, 이웃 칸) 사진도 어느 정도 맞아야 한다
        float[] green = h("그린");
        float[] lettuce = ColorHistogram.of(ColorHistogram.swatch(new Color(0x8D, 0xB2, 0x3A)));
        assertThat(ColorHistogram.bin(0x8DB23A)).isNotEqualTo(ColorHistogram.bin(0x4A9A3A));
        assertThat(ColorHistogram.similarity(green, lettuce)).isGreaterThan(ColorHistogram.similarity(green, h("레드")) + 0.2);
    }

    @Test
    void 사진은_바탕색을_덜_센다() {
        // 회색 탁자(테두리 전체) 위 가운데 빨간 물건: 그냥 세면 회색이 더 많지만, 사진용은 빨강이 더 많아야 한다
        var img = new java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new Color(150, 150, 150));
        g.fillRect(0, 0, 100, 100);
        g.setColor(new Color(0xD3, 0x2F, 0x2F));
        g.fillRect(30, 30, 40, 40);
        g.dispose();
        int gray = ColorHistogram.bin(0x969696), red = ColorHistogram.bin(0xD32F2F);
        assertThat(ColorHistogram.of(img)[gray]).isGreaterThan(ColorHistogram.of(img)[red]);
        float[] photo = ColorHistogram.ofPhoto(img);
        assertThat(photo[red]).isGreaterThan(photo[gray]);
    }

    @Test
    void 가까운_색_이름() {
        assertThat(ColorNames.nearest(new Color(20, 30, 90))).isEqualTo("네이비");
        assertThat(ColorNames.nearest(new Color(250, 250, 250))).isEqualTo("화이트");
    }
}
