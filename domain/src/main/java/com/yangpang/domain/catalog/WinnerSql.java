package com.yangpang.domain.catalog;

/**
 * 아이템 위너 선정 (기획서 8.5). 같은 SKU(item)의 판매 오퍼 중 점수가 가장 높은 하나만 is_winner.
 *
 * <pre>
 * 점수 = 가격 경쟁력 x 0.60 + 배송 x 0.25 + 판매자 점수 x 0.15
 *   가격 경쟁력 = 그 SKU 최저가 / 내 가격 x 100   (최저가면 100점)
 *   배송        = 양팡 물류(양팡배송·프레시·판매자양팡) 100점, 판매자배송 40점
 *   판매자 점수 = seller.score (0~100)
 * 품절·판매중지 오퍼는 구매 가능한 오퍼가 하나라도 있으면 위너가 될 수 없다. 리퍼 오퍼는 경쟁에서 빠진다.
 * 점수가 같으면 먼저 등록한 오퍼.
 * </pre>
 * ProductSummaryService가 요약 컬럼을 다시 계산하기 전에 실행한다.
 */
public final class WinnerSql {

    private WinnerSql() {
    }

    /** 파라미터: bigint[] (상품 id 배열) */
    public static final String RECOMPUTE_BY_PRODUCTS = """
            update vendor_item vi set is_winner = (vi.id = w.winner_id)
            from (
                select distinct on (s.item_id) s.item_id, s.id as winner_id
                from (
                    select vi2.id, vi2.item_id, vi2.created_at,
                           (vi2.stock <= 0 or vi2.status <> 'ON_SALE') as unavailable,
                           0.60 * (100.0 * min(vi2.sale_price) filter (where vi2.stock > 0 and vi2.status = 'ON_SALE')
                                       over (partition by vi2.item_id) / vi2.sale_price)
                         + 0.25 * (case when vi2.delivery_type in ('YANGPANG','FRESH','FULFILLMENT') then 100 else 40 end)
                         + 0.15 * sel.score as score
                    from vendor_item vi2
                    join item i on i.id = vi2.item_id
                    join seller sel on sel.id = vi2.seller_id
                    where i.product_id = any(?) and sel.status = 'ACTIVE' and vi2.item_condition = 'NEW'
                ) s
                order by s.item_id, s.unavailable, s.score desc nulls last, s.created_at, s.id
            ) w
            where vi.item_id = w.item_id
            """;

    /** 전체 다시 계산 */
    public static final String RECOMPUTE_ALL = RECOMPUTE_BY_PRODUCTS.replace("i.product_id = any(?) and ", "");
}
