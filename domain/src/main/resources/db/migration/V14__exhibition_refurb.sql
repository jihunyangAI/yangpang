-- =====================================================================
-- V14: 기획전 · 리퍼마켓 (기획서 5.6, 5.7)
-- =====================================================================

-- 리퍼 상품: 같은 SKU의 판매 오퍼로 두되 상태(등급)를 표시. 위너 경쟁에서는 빠진다
alter table vendor_item
    add column item_condition varchar(20) not null default 'NEW',   -- NEW / REFURB_BEST / REFURB_GOOD / REFURB_FAIR
    add column refurb_note    varchar(200);
create index idx_vendor_item_refurb on vendor_item(item_condition) where item_condition <> 'NEW';

-- 기획전
create table exhibition (
    id          bigserial primary key,
    slug        varchar(60)  not null unique,
    title       varchar(100) not null,
    subtitle    varchar(200),
    theme       varchar(20)  not null,                 -- Banner.Theme
    coupon_id   bigint references coupon(id) on delete set null,
    starts_at   timestamptz  not null,
    ends_at     timestamptz  not null,
    active      boolean      not null default true,
    sort_order  int          not null default 10,
    created_at  timestamptz  not null default now(),
    constraint ck_exhibition_period check (ends_at > starts_at)
);

create table exhibition_product (
    exhibition_id  bigint      not null references exhibition(id) on delete cascade,
    product_id     bigint      not null references product(id) on delete cascade,
    section        varchar(50) not null,
    sort_order     int         not null default 0,
    primary key (exhibition_id, product_id)
);
