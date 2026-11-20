package com.yangpang.api.web.product;

import com.yangpang.domain.catalog.search.ProductSearchCondition;
import com.yangpang.domain.catalog.search.ProductSort;
import com.yangpang.domain.common.DeliveryType;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 목록 화면의 URL 쿼리 상태 (검색어·필터·정렬·페이지).
 * 화면의 필터 링크는 이 객체의 with~/toggle~ 메서드로 만든다 → 필터 상태가 URL에 남아 뒤로가기·공유가 된다.
 */
public record ListingQuery(
        String basePath,
        String q,
        Long categoryId,
        ProductSort sort,
        Set<DeliveryType> delivery,
        Set<Long> brand,
        Long minPrice,
        Long maxPrice,
        Integer rating,
        boolean excludeSoldOut,
        int page
) {
    public ListingQuery {
        delivery = delivery == null || delivery.isEmpty() ? EnumSet.noneOf(DeliveryType.class) : EnumSet.copyOf(delivery);
        brand = brand == null ? new TreeSet<>() : new TreeSet<>(brand);
        if (sort == null) sort = ProductSort.RANKING;
        if (page < 1) page = 1;
        if (q != null && q.isBlank()) q = null;
    }

    public ProductSearchCondition toCondition(Set<Long> categoryIds) {
        return new ProductSearchCondition(categoryIds, q, delivery, brand, minPrice, maxPrice, rating, excludeSoldOut);
    }

    public boolean hasFilters() {
        return !delivery.isEmpty() || !brand.isEmpty() || minPrice != null || maxPrice != null
                || rating != null || excludeSoldOut;
    }

    // ---------- URL 만들기 ----------
    public String url() {
        UriComponentsBuilder u = UriComponentsBuilder.fromPath(basePath);
        if (q != null) u.queryParam("q", q);
        if (categoryId != null && basePath.startsWith("/search")) u.queryParam("categoryId", categoryId);
        if (sort != ProductSort.RANKING) u.queryParam("sort", sort.name().toLowerCase());
        delivery.forEach(d -> u.queryParam("delivery", d.name()));
        brand.forEach(b -> u.queryParam("brand", b));
        if (minPrice != null) u.queryParam("minPrice", minPrice);
        if (maxPrice != null) u.queryParam("maxPrice", maxPrice);
        if (rating != null) u.queryParam("rating", rating);
        if (excludeSoldOut) u.queryParam("excludeSoldOut", true);
        if (page > 1) u.queryParam("page", page);
        return u.encode().build().toUriString();
    }

    public String withSort(ProductSort s) {
        return copy(s, delivery, brand, minPrice, maxPrice, rating, excludeSoldOut, 1).url();
    }

    public String toggleDelivery(DeliveryType t) {
        Set<DeliveryType> next = EnumSet.noneOf(DeliveryType.class);
        next.addAll(delivery);
        if (!next.remove(t)) next.add(t);
        return copy(sort, next, brand, minPrice, maxPrice, rating, excludeSoldOut, 1).url();
    }

    public String toggleBrand(Long id) {
        Set<Long> next = new TreeSet<>(brand);
        if (!next.remove(id)) next.add(id);
        return copy(sort, delivery, next, minPrice, maxPrice, rating, excludeSoldOut, 1).url();
    }

    public String withPrice(Long min, Long max) {
        return copy(sort, delivery, brand, min, max, rating, excludeSoldOut, 1).url();
    }

    public boolean isPrice(Long min, Long max) {
        return java.util.Objects.equals(minPrice, min) && java.util.Objects.equals(maxPrice, max);
    }

    public String toggleRating(int r) {
        return copy(sort, delivery, brand, minPrice, maxPrice, rating != null && rating == r ? null : r, excludeSoldOut, 1).url();
    }

    public String toggleSoldOut() {
        return copy(sort, delivery, brand, minPrice, maxPrice, rating, !excludeSoldOut, 1).url();
    }

    public String withPage(int p) {
        return copy(sort, delivery, brand, minPrice, maxPrice, rating, excludeSoldOut, p).url();
    }

    /** 무한 스크롤용: 다음 페이지 상품 조각만 */
    public String nextItemsUrl() {
        String next = withPage(page + 1);
        return next + (next.contains("?") ? "&" : "?") + "fragment=items";
    }

    public String clearFilters() {
        return copy(sort, null, null, null, null, null, false, 1).url();
    }

    private ListingQuery copy(ProductSort s, Set<DeliveryType> d, Set<Long> b, Long min, Long max, Integer r,
                              boolean soldOut, int p) {
        return new ListingQuery(basePath, q, categoryId, s, d, b, min, max, r, soldOut, p);
    }
}
