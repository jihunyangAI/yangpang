-- =====================================================================
-- V4: 시뮬레이터 (기획서 7.4)
-- =====================================================================

-- 시뮬레이션 시계: 시뮬레이션 시각 = base_sim + (지금 실제 시각 - base_real) x speed
create table sim_setting (
    id         int          primary key default 1 check (id = 1),
    base_real  timestamptz  not null default now(),
    base_sim   timestamptz  not null default now(),
    speed      numeric(10,2) not null default 1,
    auto_run   boolean      not null default true      -- 배송 자동 진행 작업 실행 여부
);
insert into sim_setting(id) values (1);

-- 주문 상품 단위로 배송을 진행한다 (양팡배송과 판매자배송은 도착일이 다름)
alter table order_item
    add column status_changed_at  timestamptz,
    add column delivery_photo_url varchar(500);

update order_item oi
set status_changed_at = coalesce(oi.confirmed_at, oi.delivered_at, o.paid_at, o.ordered_at)
from orders o
where o.id = oi.order_id;

update order_item set delivery_photo_url = '/images/delivery/' || id || '.svg' where delivered_at is not null;

alter table order_item alter column status_changed_at set not null;

create index idx_order_item_status on order_item(status);
create index idx_orders_status on orders(status);
