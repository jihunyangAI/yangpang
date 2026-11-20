-- =====================================================================
-- V2: 상품 목록·검색용 요약 컬럼 (비정규화)
-- 상품마다 대표 판매 오퍼(구매 가능한 위너 중 최저가)의 값을 복사해 둔다.
-- 판매 오퍼의 가격·재고가 바뀌면 ProductSummaryService가 다시 계산한다.
-- =====================================================================
alter table product
    add column rep_vendor_item_id bigint references vendor_item(id) on delete set null,
    add column rep_sale_price     bigint      not null default 0,
    add column rep_list_price     bigint      not null default 0,
    add column rep_delivery_type  varchar(20),
    add column sold_out           boolean     not null default false;

-- 기존 데이터 채우기
update product p
set rep_vendor_item_id = r.vendor_item_id,
    rep_sale_price     = r.sale_price,
    rep_list_price     = r.list_price,
    rep_delivery_type  = r.delivery_type,
    sold_out           = r.sold_out
from (
    select distinct on (i.product_id)
           i.product_id, vi.id as vendor_item_id, vi.sale_price, vi.list_price, vi.delivery_type,
           (vi.stock <= 0 or vi.status <> 'ON_SALE') as sold_out
    from vendor_item vi
    join item i on i.id = vi.item_id
    where vi.is_winner
    order by i.product_id, (vi.stock <= 0 or vi.status <> 'ON_SALE'), vi.sale_price
) r
where r.product_id = p.id;

create index idx_product_rep_price on product(rep_sale_price);
create index idx_product_sales on product(sales_count desc);
create index idx_product_created on product(created_at desc);
