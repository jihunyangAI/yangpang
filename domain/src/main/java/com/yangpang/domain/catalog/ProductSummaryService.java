package com.yangpang.domain.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;

/** 판매 오퍼의 가격·재고·상태가 바뀐 뒤 호출해서 상품 요약 컬럼을 맞추고, 검색 색인에도 알린다 */
@Service
@RequiredArgsConstructor
public class ProductSummaryService {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    /** 위너를 다시 고르고(WinnerSql) → 요약 컬럼 갱신 → 검색 색인에 알림 */
    @Transactional
    public void refresh(Collection<Long> productIds) {
        if (productIds.isEmpty()) return;
        jdbc.update(con -> {
            var ps = con.prepareStatement(WinnerSql.RECOMPUTE_BY_PRODUCTS);
            ps.setArray(1, con.createArrayOf("bigint", productIds.toArray()));
            return ps;
        });
        jdbc.update(con -> {
            var ps = con.prepareStatement(ProductSummarySql.REFRESH_BY_IDS);
            ps.setArray(1, con.createArrayOf("bigint", productIds.toArray()));
            return ps;
        });
        events.publishEvent(new ProductsChangedEvent(Set.copyOf(productIds)));
    }

    @Transactional
    public void refreshAll() {
        jdbc.update(WinnerSql.RECOMPUTE_ALL);
        jdbc.update(ProductSummarySql.REFRESH_ALL);
        events.publishEvent(new ProductsChangedEvent(Set.of()));
    }

    /** 요약 컬럼은 그대로지만 이름·카테고리·별점 등이 바뀐 경우 색인만 갱신 */
    public void changed(Collection<Long> productIds) {
        if (!productIds.isEmpty()) events.publishEvent(new ProductsChangedEvent(Set.copyOf(productIds)));
    }
}
