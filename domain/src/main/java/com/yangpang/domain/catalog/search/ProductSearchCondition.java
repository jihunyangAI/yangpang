package com.yangpang.domain.catalog.search;

import com.yangpang.domain.common.DeliveryType;

import java.util.List;
import java.util.Set;

/**
 * 상품 목록·검색 조건.
 *
 * @param categoryIds    선택한 카테고리와 그 하위 카테고리 id 전체 (비어 있으면 전체)
 * @param keyword        검색어 (공백으로 나눈 단어가 모두 들어간 상품)
 * @param deliveryTypes  배송 유형 필터
 * @param brandIds       브랜드 필터
 * @param minPrice       최소 가격 (대표 판매가 기준)
 * @param maxPrice       최대 가격
 * @param minRating      별점 하한 (예: 4 → 4.0 이상)
 * @param excludeSoldOut 품절 제외
 */
public record ProductSearchCondition(
        Set<Long> categoryIds,
        String keyword,
        Set<DeliveryType> deliveryTypes,
        Set<Long> brandIds,
        Long minPrice,
        Long maxPrice,
        Integer minRating,
        boolean excludeSoldOut
) {
    public ProductSearchCondition {
        categoryIds = categoryIds == null ? Set.of() : Set.copyOf(categoryIds);
        deliveryTypes = deliveryTypes == null ? Set.of() : Set.copyOf(deliveryTypes);
        brandIds = brandIds == null ? Set.of() : Set.copyOf(brandIds);
        keyword = keyword == null ? null : keyword.trim();
        if (keyword != null && keyword.isEmpty()) keyword = null;
    }

    public List<String> keywordTokens() {
        if (keyword == null) return List.of();
        return List.of(keyword.toLowerCase().split("\\s+"));
    }

    /** 브랜드 개수 집계용: 브랜드 필터만 뺀 조건 */
    public ProductSearchCondition withoutBrands() {
        return new ProductSearchCondition(categoryIds, keyword, deliveryTypes, Set.of(), minPrice, maxPrice,
                minRating, excludeSoldOut);
    }
}
