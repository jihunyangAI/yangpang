package com.yangpang.domain.imagesearch;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 이미지 검색용 색 분포 (M4 ⑦, 외부 모델 없이 단순 유사도).
 * <pre>
 * 이미지를 64x64로 줄여 픽셀마다 HSV로 바꾸고 52칸에 나눠 센 뒤 비율로 만든다.
 *   무채색(채도 &lt; 0.15) 4칸 : 명도 0~0.25 검정, ~0.5 진회색, ~0.85 회색, 그 이상 흰색
 *   유채색 48칸            : 색상 12 x 채도 2(0.5 기준) x 명도 2(0.5 기준)
 * 유사도 = 칸마다 작은 값의 합(히스토그램 교집합, 0~1). 배경이 되기 쉬운 흰색 칸은 0.3배로 덜 센다.
 * </pre>
 */
public final class ColorHistogram {

    public static final int GRAY_BINS = 4;
    public static final int HUE_BINS = 12;
    public static final int BINS = GRAY_BINS + HUE_BINS * 4;
    static final int SIZE = 64;
    static final double WHITE_WEIGHT = 0.3;
    static final int WHITE_BIN = 3;

    private ColorHistogram() {
    }

    public static int bin(int rgb) {
        float[] hsv = Color.RGBtoHSB((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, null);
        float h = hsv[0], s = hsv[1], v = hsv[2];
        if (s < 0.15f || v < 0.12f) {
            return v < 0.25f ? 0 : v < 0.5f ? 1 : v < 0.85f ? 2 : WHITE_BIN;
        }
        int hb = Math.min(HUE_BINS - 1, (int) (h * HUE_BINS));
        int sb = s < 0.5f ? 0 : 1;
        int vb = v < 0.5f ? 0 : 1;
        return GRAY_BINS + hb * 4 + sb * 2 + vb;
    }

    /** 이미지 → 52칸 비율 (합 1). 투명 픽셀은 흰 바탕으로 본다 */
    public static float[] of(BufferedImage source) {
        BufferedImage small = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, SIZE, SIZE);
        g.drawImage(source, 0, 0, SIZE, SIZE, null);
        g.dispose();
        float[] h = new float[BINS];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) h[bin(small.getRGB(x, y))]++;
        }
        float total = SIZE * SIZE;
        for (int i = 0; i < BINS; i++) h[i] /= total;
        return h;
    }

    /**
     * 히스토그램 교집합 (흰색 칸은 덜 센다) → 0~1.
     * 찾는 사진(query) 기준으로 나눠, 찾는 사진의 색이 상품 이미지에 얼마나 들어 있는지를 본다.
     */
    public static double similarity(float[] query, float[] product) {
        double sum = 0, total = 0;
        for (int i = 0; i < BINS; i++) {
            double w = i == WHITE_BIN ? WHITE_WEIGHT : 1;
            sum += w * Math.min(query[i], product[i]);
            total += w * query[i];
        }
        return total == 0 ? 0 : sum / total;
    }

    /** 칸의 대표 색 (화면 표시용) */
    public static Color binColor(int bin) {
        if (bin < GRAY_BINS) {
            int v = new int[]{30, 95, 160, 240}[bin];
            return new Color(v, v, v);
        }
        int i = bin - GRAY_BINS;
        int hb = i / 4, sb = (i % 4) / 2, vb = i % 2;
        return Color.getHSBColor((hb + 0.5f) / HUE_BINS, sb == 0 ? 0.35f : 0.75f, vb == 0 ? 0.35f : 0.8f);
    }

    /** 비율이 큰 칸 n개 (minShare 이상) */
    public static List<Integer> topBins(float[] h, int n, double minShare) {
        return IntStream.range(0, BINS).boxed().filter(i -> h[i] >= minShare)
                .sorted(Comparator.comparingDouble((Integer i) -> -h[i])).limit(n).toList();
    }

    /** 한 가지 색으로 칠한 가짜 사진 (색으로 찾기·테스트용): 흰 바탕 가운데에 그 색 물건 */
    public static BufferedImage swatch(Color color) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(245, 245, 243));
        g.fillRect(0, 0, SIZE, SIZE);
        g.setColor(color);
        g.fillRoundRect(12, 10, 40, 44, 10, 10);
        g.dispose();
        return img;
    }

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}
