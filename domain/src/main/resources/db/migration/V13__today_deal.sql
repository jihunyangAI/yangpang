-- =====================================================================
-- V13: 오늘의 특가 (기획서 5.6, 시나리오 8)
-- 매일 07:00(한국 시간)에 바뀌는 한정 수량 특가. 수량은 조건부 UPDATE + CHECK 제약으로 초과 판매를 막는다.
-- =====================================================================
create table deal (
    id              bigserial primary key,
    vendor_item_id  bigint       not null references vendor_item(id) on delete cascade,
    deal_price      bigint       not null check (deal_price > 0),
    quantity        int          not null check (quantity > 0),
    sold            int          not null default 0,
    max_per_order   int          not null default 2,
    max_per_member  int          not null default 2,
    starts_at       timestamptz  not null,
    ends_at         timestamptz  not null,
    source          varchar(10)  not null default 'AUTO',   -- AUTO(매일 자동 교체) / ADMIN(운영자 등록)
    created_at      timestamptz  not null default now(),
    constraint ck_deal_sold check (sold >= 0 and sold <= quantity),
    constraint ck_deal_period check (ends_at > starts_at)
);
create index idx_deal_period on deal(starts_at, ends_at);
create index idx_deal_vendor_item on deal(vendor_item_id);

alter table order_item add column deal_id bigint references deal(id) on delete set null;
create index idx_order_item_deal on order_item(deal_id) where deal_id is not null;
