package com.yangpang.domain.catalog;

/**
 * 상품 요약 컬럼(rep_*, sold_out) 재계산 SQL.
 * 웹 앱(ProductSummaryService)과 가짜 데이터 생성기(seed)가 함께 쓴다.
 * 대표 오퍼 = 위너 오퍼 중 구매 가능한 것 우선, 그다음 최저가.
 */
public final class ProductSummarySql {

    private ProductSummarySql() {
    }

    private static final String BASE = """
            update product p
            set rep_vendor_item_id = r.vendor_item_id,
                rep_sale_price     = r.sale_price,
                rep_list_price     = r.list_price,
                rep_plus_price     = r.plus_price,
                rep_delivery_type  = r.delivery_type,
                sold_out           = r.sold_out,
                updated_at         = now()
            from (
                select distinct on (i.product_id)
                       i.product_id, vi.id as vendor_item_id, vi.sale_price, vi.list_price, vi.plus_price, vi.delivery_type,
                       (vi.stock <= 0 or vi.status <> 'ON_SALE') as sold_out
                from vendor_item vi
                join item i on i.id = vi.item_id
                where vi.is_winner %s
                order by i.product_id, (vi.stock <= 0 or vi.status <> 'ON_SALE'), vi.sale_price
            ) r
            where r.product_id = p.id
            """;

    /** 전체 상품 재계산 */
    public static final String REFRESH_ALL = BASE.formatted("");

    /** 지정한 상품만 재계산. 파라미터: bigint[] (상품 id 배열) */
    public static final String REFRESH_BY_IDS = BASE.formatted("and i.product_id = any(?)");
}
