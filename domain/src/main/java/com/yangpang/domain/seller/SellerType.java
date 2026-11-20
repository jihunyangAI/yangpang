package com.yangpang.domain.seller;

/** 판매자 유형: 1P 직매입 / 2P 풀필먼트 / 3P 마켓플레이스 */
public enum SellerType {
    DIRECT("양팡 직매입"),
    FULFILLMENT("판매자양팡"),
    MARKETPLACE("판매자배송");

    private final String label;

    SellerType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
