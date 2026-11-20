package com.yangpang.api.web.product;

import java.math.BigDecimal;

/**
 * 상품 카드 공통 컴포넌트의 화면 데이터 (기획서 D-04, 레퍼런스 4장).
 * 목록·홈·검색·추천 등 모든 상품 목록에서 재사용한다.
 */
public record ProductCard(
        Long productId,
        String name,
        String imageUrl,
        long salePrice,
        long listPrice,
        int discountRate,
        Long plusPrice,
        String deliveryType,
        String deliveryLabel,
        String arrivalText,
        BigDecimal rating,
        int reviewCount,
        boolean soldOut,
        boolean adult        // 19세 이상 상품 (인증 안 했으면 이미지 가림)
) {
    public boolean hasDiscount() {
        return discountRate > 0;
    }

    /** 별점을 0~100% 너비로 (별 아이콘 채우기용) */
    public int ratingPercent() {
        return rating == null ? 0 : rating.multiply(BigDecimal.valueOf(20)).intValue();
    }
}
