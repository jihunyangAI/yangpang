package com.yangpang.api.search;

import com.yangpang.domain.catalog.Brand;
import com.yangpang.domain.catalog.search.ProductSearchCondition;
import com.yangpang.domain.catalog.search.ProductSearchRepository;
import com.yangpang.domain.catalog.search.ProductSort;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 키워드 상품 검색 (Meilisearch). 오타 교정·동의어·관련도 순.
 * 결과를 못 받으면 Optional.empty() → 호출하는 쪽이 PostgreSQL 검색으로 대신한다.
 */
@Component
@RequiredArgsConstructor
public class MeiliProductSearch {

    public record Result(List<Long> ids, long total, List<ProductSearchRepository.BrandFacet> brandFacets) {
    }

    private final MeiliClient meili;
    private final EntityManager em;

    public Optional<Result> search(ProductSearchCondition cond, ProductSort sort, int page, int size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", cond.keyword() == null ? "" : cond.keyword());
        body.put("page", page);
        body.put("hitsPerPage", size);
        body.put("attributesToRetrieve", List.of("id"));
        body.put("matchingStrategy", "all"); // 검색어의 모든 단어가 들어간 상품만
        body.put("filter", filters(cond, true));
        List<String> sortBy = sortOf(sort);
        if (!sortBy.isEmpty()) body.put("sort", sortBy);

        Map<String, Object> r = meili.search(body);
        if (r == null) return Optional.empty();
        List<Long> ids = ((List<?>) r.get("hits")).stream()
                .map(h -> ((Number) ((Map<?, ?>) h).get("id")).longValue()).toList();
        long total = ((Number) r.getOrDefault("totalHits", 0)).longValue();

        // 브랜드 개수는 브랜드 필터를 뺀 조건으로 (필터 목록에 다른 브랜드도 보이도록)
        Map<String, Object> facetBody = new LinkedHashMap<>();
        facetBody.put("q", body.get("q"));
        facetBody.put("matchingStrategy", "all");
        facetBody.put("limit", 0);
        facetBody.put("facets", List.of("brandId"));
        facetBody.put("filter", filters(cond, false));
        Map<String, Object> f = meili.search(facetBody);
        return Optional.of(new Result(ids, total, brandFacets(f)));
    }

    /** 자동완성: 상품명 앞쪽 몇 개 */
    public Optional<List<String>> suggestNames(String q, int limit) {
        Map<String, Object> r = meili.search(Map.of("q", q, "limit", limit, "attributesToRetrieve", List.of("name"),
                "filter", "soldOut = false"));
        if (r == null) return Optional.empty();
        return Optional.of(((List<?>) r.get("hits")).stream().map(h -> (String) ((Map<?, ?>) h).get("name")).toList());
    }

    private static List<String> filters(ProductSearchCondition c, boolean withBrand) {
        List<String> f = new ArrayList<>();
        if (!c.categoryIds().isEmpty()) f.add("categoryIds IN " + c.categoryIds());
        if (!c.deliveryTypes().isEmpty()) {
            f.add("deliveryType IN [" + c.deliveryTypes().stream().map(d -> "\"" + d.name() + "\"").collect(Collectors.joining(",")) + "]");
        }
        if (withBrand && !c.brandIds().isEmpty()) f.add("brandId IN " + c.brandIds());
        if (c.minPrice() != null) f.add("price >= " + c.minPrice());
        if (c.maxPrice() != null) f.add("price <= " + c.maxPrice());
        if (c.minRating() != null) f.add("rating >= " + c.minRating());
        if (c.excludeSoldOut()) f.add("soldOut = false");
        return f;
    }

    private static List<String> sortOf(ProductSort sort) {
        return switch (sort) {
            case RANKING -> List.of();                    // 관련도 + 양팡 랭킹 점수 (색인 랭킹 규칙)
            case PRICE_ASC -> List.of("price:asc");
            case PRICE_DESC -> List.of("price:desc");
            case SALES -> List.of("salesCount:desc");
            case LATEST -> List.of("createdAt:desc");
            case REVIEWS -> List.of("reviewCount:desc");
        };
    }

    private List<ProductSearchRepository.BrandFacet> brandFacets(Map<String, Object> f) {
        if (f == null || !(f.get("facetDistribution") instanceof Map<?, ?> dist)
                || !(dist.get("brandId") instanceof Map<?, ?> counts) || counts.isEmpty()) return List.of();
        Map<Long, Long> byId = new LinkedHashMap<>();
        counts.forEach((k, v) -> byId.put(Long.parseLong(String.valueOf(k)), ((Number) v).longValue()));
        Map<Long, String> names = em.createQuery("select b from Brand b where b.id in :ids", Brand.class)
                .setParameter("ids", byId.keySet()).getResultList().stream()
                .collect(Collectors.toMap(Brand::getId, Brand::getName));
        return byId.entrySet().stream()
                .filter(e -> names.containsKey(e.getKey()))
                .map(e -> new ProductSearchRepository.BrandFacet(e.getKey(), names.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparingLong(ProductSearchRepository.BrandFacet::count).reversed()
                        .thenComparing(ProductSearchRepository.BrandFacet::name))
                .limit(15).toList();
    }
}
