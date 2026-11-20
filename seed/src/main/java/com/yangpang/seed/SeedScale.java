package com.yangpang.seed;

/**
 * 데이터 규모 프리셋 (기획서 7.2).
 * 지금은 MVP만 정의한다. small/default/large는 해당 기능을 붙이는 단계에서 추가한다.
 */
public enum SeedScale {
    MVP(50, 1_000, 100, 300, 5_000, 500);

    public final int brands;
    public final int products;
    public final int members;
    public final int orders;
    public final int reviews;    // M2
    public final int inquiries;  // M2

    SeedScale(int brands, int products, int members, int orders, int reviews, int inquiries) {
        this.brands = brands;
        this.products = products;
        this.members = members;
        this.orders = orders;
        this.reviews = reviews;
        this.inquiries = inquiries;
    }
}
