package com.yangpang.domain.common;

/** 배송 권역 (도착일·추가 배송비 계산용) */
public enum Region {
    METRO("수도권"),
    LOCAL("지방"),
    REMOTE("도서산간");

    private final String label;

    Region(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
