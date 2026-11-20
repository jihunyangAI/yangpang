package com.yangpang.domain.catalog.search;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.ProductStatus;
import com.yangpang.domain.catalog.QBrand;
import com.yangpang.domain.catalog.QCategory;
import com.yangpang.domain.catalog.QProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/** 상품 목록·검색 (QueryDSL). MVP는 PostgreSQL로 검색하고, M2에서 Meilisearch로 옮긴다 */
@Repository
@RequiredArgsConstructor
public class ProductSearchRepository {

    public record BrandFacet(Long brandId, String name, long count) {
    }

    private static final QProduct p = QProduct.product;
    private static final QBrand b = QBrand.brand;
    private static final QCategory c = QCategory.category;

    private final JPAQueryFactory query;

    public Page<Product> search(ProductSearchCondition cond, ProductSort sort, Pageable pageable) {
        BooleanBuilder where = where(cond);

        List<Product> content = query.selectFrom(p)
                .leftJoin(p.brand, b).fetchJoin()
                .join(p.category, c)
                .where(where)
                .orderBy(orders(sort))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = query.select(p.count())
                .from(p)
                .leftJoin(p.brand, b)
                .join(p.category, c)
                .where(where)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /** 관리자 상품 목록: 판매중지 포함, 상품명·브랜드·id 검색 */
    public Page<Product> adminSearch(String keyword, ProductStatus status, Pageable pageable) {
        BooleanBuilder w = new BooleanBuilder();
        if (status != null) w.and(p.status.eq(status));
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim().toLowerCase();
            BooleanExpression match = p.name.lower().contains(k).or(b.name.lower().contains(k));
            if (k.matches("\\d+")) match = match.or(p.id.eq(Long.parseLong(k)));
            w.and(match);
        }
        List<Product> content = query.selectFrom(p)
                .leftJoin(p.brand, b).fetchJoin()
                .join(p.category, c).fetchJoin()
                .where(w)
                .orderBy(p.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = query.select(p.count()).from(p).leftJoin(p.brand, b).where(w).fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /** 현재 조건(브랜드 필터 제외)에서 브랜드별 상품 수 */
    public List<BrandFacet> brandFacets(ProductSearchCondition cond, int limit) {
        BooleanBuilder where = where(cond.withoutBrands());
        return query.select(b.id, b.name, p.count())
                .from(p)
                .join(p.brand, b)
                .join(p.category, c)
                .where(where)
                .groupBy(b.id, b.name)
                .orderBy(p.count().desc(), b.name.asc())
                .limit(limit)
                .fetch()
                .stream()
                .map(t -> new BrandFacet(t.get(b.id), t.get(b.name), t.get(p.count())))
                .toList();
    }

    private BooleanBuilder where(ProductSearchCondition cond) {
        BooleanBuilder w = new BooleanBuilder(p.status.eq(ProductStatus.ON_SALE));
        if (!cond.categoryIds().isEmpty()) w.and(p.category.id.in(cond.categoryIds()));
        for (String token : cond.keywordTokens()) {
            w.and(matches(token));
        }
        if (!cond.deliveryTypes().isEmpty()) w.and(p.repDeliveryType.in(cond.deliveryTypes()));
        if (!cond.brandIds().isEmpty()) w.and(p.brand.id.in(cond.brandIds()));
        if (cond.minPrice() != null) w.and(p.repSalePrice.goe(cond.minPrice()));
        if (cond.maxPrice() != null) w.and(p.repSalePrice.loe(cond.maxPrice()));
        if (cond.minRating() != null) w.and(p.ratingAvg.goe(BigDecimal.valueOf(cond.minRating())));
        if (cond.excludeSoldOut()) w.and(p.soldOut.isFalse());
        return w;
    }

    /** 단어가 상품명·브랜드명·카테고리명 중 하나에 들어 있으면 일치 */
    private BooleanExpression matches(String token) {
        return p.name.lower().contains(token)
                .or(b.name.lower().contains(token))
                .or(c.name.lower().contains(token));
    }

    private OrderSpecifier<?>[] orders(ProductSort sort) {
        return switch (sort) {
            // 양팡 랭킹: 판매량·조회·전환율·리뷰·가격·배송·반품률 가중합 (RankingService)
            case RANKING -> new OrderSpecifier[]{p.soldOut.asc(), p.rankingScore.desc(), p.salesCount.desc(), p.id.asc()};
            case PRICE_ASC -> new OrderSpecifier[]{p.repSalePrice.asc(), p.id.asc()};
            case PRICE_DESC -> new OrderSpecifier[]{p.repSalePrice.desc(), p.id.asc()};
            case SALES -> new OrderSpecifier[]{p.salesCount.desc(), p.id.asc()};
            case LATEST -> new OrderSpecifier[]{p.createdAt.desc(), p.id.desc()};
            case REVIEWS -> new OrderSpecifier[]{p.reviewCount.desc(), p.id.asc()};
        };
    }
}
