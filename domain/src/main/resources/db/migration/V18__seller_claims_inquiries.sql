-- =====================================================================
-- V18: M4 ① 이월 — 판매자 반품·교환 승인/거절, 판매자 상품문의 답변
-- =====================================================================

-- 판매자배송 상품만 담긴 반품·교환은 그 판매자가 검수 후 승인·거절한다
alter table claim add column seller_id bigint references seller(id);
alter table claim add column reject_reason varchar(300);
alter table claim add column decided_by varchar(20);   -- SELLER / SYSTEM(판매자 미처리 자동 승인) / ADMIN
create index idx_claim_seller on claim(seller_id, status) where seller_id is not null;

update claim c set seller_id = x.sid
from (select ci.claim_id, min(oi.seller_id) as sid
        from claim_item ci join order_item oi on oi.id = ci.order_item_id
       group by ci.claim_id
      having count(distinct oi.seller_id) = 1
         and bool_and(oi.delivery_type not in ('YANGPANG', 'FRESH', 'FULFILLMENT'))) x
where x.claim_id = c.id and c.claim_type <> 'CANCEL';

-- 상품문의 답변자: 판매자(그 상품을 파는 판매자) 또는 운영자
alter table product_inquiry add column answered_by varchar(20);   -- SELLER / ADMIN
alter table product_inquiry add column answer_seller_id bigint references seller(id);
update product_inquiry set answered_by = 'ADMIN' where answer is not null;
