package com.yangpang.domain.common;

/** 배송 유형 (기획서 1.3, 5.9) */
public enum DeliveryType {
    YANGPANG("양팡배송"),
    FRESH("양팡프레시"),
    FULFILLMENT("판매자양팡"),
    SELLER("판매자배송"),
    GLOBAL("양팡직구"),
    INSTALL("양팡설치");

    private final String label;

    DeliveryType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 양팡 물류로 배송되는지 (무료배송 기준이 양팡 정책을 따름) */
    public boolean isYangpangLogistics() {
        return this == YANGPANG || this == FRESH || this == FULFILLMENT;
    }
}
