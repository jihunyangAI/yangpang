package com.yangpang.domain.coupon;

import java.util.Set;

/**
 * 할인 계산에 필요한 쿠폰 정보 (순수 값).
 * @param memberCouponId    회원이 가진 쿠폰 id (사용 표시용)
 * @param targetCategoryId  null이면 전체. 상품의 카테고리 경로에 이 id가 있으면 대상
 */
public record CouponSpec(long memberCouponId, String name, Kind kind, DiscountType discountType, long value,
                         Long maxDiscount, long minOrderAmount, Long targetCategoryId, Long targetProductId) {

    public enum Kind {PRODUCT, CART}

    public enum DiscountType {AMOUNT, RATE}

    /** 이 상품에 쓸 수 있는 쿠폰인지 (카테고리 경로는 대분류~소분류 id 집합) */
    public boolean appliesTo(long productId, Set<Long> categoryPath) {
        if (targetProductId != null && targetProductId != productId) return false;
        return targetCategoryId == null || categoryPath.contains(targetCategoryId);
    }

    /** 대상 금액에 대한 할인액 (10원 단위 내림, 대상 금액을 넘지 않음). 최소 주문금액 미달이면 0 */
    public long discountFor(long amount) {
        if (amount <= 0 || amount < minOrderAmount) return 0;
        long d = discountType == DiscountType.AMOUNT ? value : amount * value / 100;
        if (maxDiscount != null) d = Math.min(d, maxDiscount);
        d = Math.min(d, amount);
        return d / 10 * 10;
    }

    public String benefitText() {
        return discountType == DiscountType.AMOUNT ? String.format("%,d원", value)
                : value + "%" + (maxDiscount == null ? "" : String.format(" (최대 %,d원)", maxDiscount));
    }
}
