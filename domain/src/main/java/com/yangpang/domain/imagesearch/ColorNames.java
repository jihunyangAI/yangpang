package com.yangpang.domain.imagesearch;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 색상 옵션 이름 → 실제 색 (가짜 상품 이미지·색으로 찾기용, M4 ⑦) */
public final class ColorNames {

    private ColorNames() {
    }

    public static final Map<String, Color> COLORS = new LinkedHashMap<>();

    static {
        COLORS.put("블랙", new Color(0x23, 0x23, 0x26));
        COLORS.put("화이트", new Color(0xF7, 0xF7, 0xF5));
        COLORS.put("그레이", new Color(0x8C, 0x90, 0x96));
        COLORS.put("네이비", new Color(0x1F, 0x2F, 0x5E));
        COLORS.put("베이지", new Color(0xD8, 0xC0, 0x9A));
        COLORS.put("핑크", new Color(0xEE, 0x86, 0xAE));
        COLORS.put("그린", new Color(0x4A, 0x9A, 0x3A));   // 실제 사진(채소·식물)에 흔한 초록 (색상 약 110°)
        COLORS.put("레드", new Color(0xD3, 0x2F, 0x2F));
        COLORS.put("블루", new Color(0x2F, 0x6F, 0xD8));
        COLORS.put("옐로우", new Color(0xF2, 0xC9, 0x2E));
        COLORS.put("브라운", new Color(0x7A, 0x4E, 0x2D));
        COLORS.put("퍼플", new Color(0x7E, 0x4F, 0xB8));
        COLORS.put("오렌지", new Color(0xF0, 0x7D, 0x24));
    }

    public static Optional<Color> of(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(COLORS.get(name.trim()));
    }

    /** 가장 가까운 색 이름 (RGB 거리) */
    public static String nearest(Color c) {
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (var e : COLORS.entrySet()) {
            Color x = e.getValue();
            double d = Math.pow(c.getRed() - x.getRed(), 2) + Math.pow(c.getGreen() - x.getGreen(), 2) + Math.pow(c.getBlue() - x.getBlue(), 2);
            if (d < bestD) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }
}
