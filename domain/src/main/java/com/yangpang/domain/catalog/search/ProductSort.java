package com.yangpang.domain.catalog.search;

/** 상품 목록 정렬 6종 (기획서 D-05) */
public enum ProductSort {
    RANKING("양팡 랭킹순"),
    PRICE_ASC("낮은가격순"),
    PRICE_DESC("높은가격순"),
    SALES("판매량순"),
    LATEST("최신순"),
    REVIEWS("리뷰 많은순");

    private final String label;

    ProductSort(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ProductSort from(String value) {
        if (value == null || value.isBlank()) return RANKING;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RANKING;
        }
    }
}
