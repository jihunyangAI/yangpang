package com.yangpang.domain.catalog;

import java.util.Set;

/**
 * 상품의 가격·재고·별점·이름·상태 등이 바뀌었다는 알림.
 * 검색 색인(Meilisearch)이 이 이벤트를 받아 해당 상품만 다시 넣는다.
 * @param productIds 바뀐 상품. 비어 있으면 "전체가 바뀜"
 */
public record ProductsChangedEvent(Set<Long> productIds) {

    public boolean all() {
        return productIds.isEmpty();
    }
}
