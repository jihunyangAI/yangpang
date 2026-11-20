-- =====================================================================
-- V15: 알림함 (기획서 5.11) · 찜 가격 인하/재입고 알림
-- =====================================================================
create table notification (
    id          bigserial primary key,
    member_id   bigint       not null references member(id) on delete cascade,
    type        varchar(20)  not null,     -- ORDER / DELIVERY / CLAIM / PRICE_DROP / RESTOCK / BENEFIT
    title       varchar(100) not null,
    body        varchar(300),
    link        varchar(200),
    image_url   varchar(200),
    read_at     timestamptz,
    created_at  timestamptz  not null
);
create index idx_notification_member on notification(member_id, created_at desc);
create index idx_notification_unread on notification(member_id) where read_at is null;

-- 찜한 순간(또는 마지막으로 알린) 가격과 품절 여부: 이보다 싸지거나 재입고되면 알림
alter table wishlist
    add column notified_price bigint,
    add column was_sold_out   boolean not null default false;
update wishlist w set notified_price = p.rep_sale_price, was_sold_out = p.sold_out from product p where p.id = w.product_id;

-- 회원별 알림 받기 설정 (꺼 두면 그 종류는 만들지 않음)
alter table member
    add column notify_delivery boolean not null default true,
    add column notify_wish     boolean not null default true;
