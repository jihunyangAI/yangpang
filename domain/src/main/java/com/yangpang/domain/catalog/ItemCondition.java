package com.yangpang.domain.catalog;

/**
 * 상품 상태. 리퍼(반품·전시·포장 훼손 상품을 검수해 다시 파는 것)는 등급별로 할인한다 (기획서 5.7 리퍼마켓)
 */
public enum ItemCondition {
    NEW("새 상품", "", 1.0),
    REFURB_BEST("리퍼 최상", "포장만 개봉, 사용 흔적 없음", 0.85),
    REFURB_GOOD("리퍼 상", "외관에 아주 작은 흠집, 기능 정상", 0.72),
    REFURB_FAIR("리퍼 중", "외관 흠집 있음, 점검·수리 후 기능 정상", 0.55);

    private final String label;
    private final String description;
    private final double priceRate;

    ItemCondition(String label, String description, double priceRate) {
        this.label = label;
        this.description = description;
        this.priceRate = priceRate;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 새 상품 판매가 대비 리퍼 가격 비율 */
    public double priceRate() {
        return priceRate;
    }

    public boolean isRefurb() {
        return this != NEW;
    }
}
