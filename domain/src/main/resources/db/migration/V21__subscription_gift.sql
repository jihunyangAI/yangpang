-- =====================================================================
-- V21: M4 ④ 정기배송(주기 자동 주문, 모의 결제) · 선물하기(받는 분이 링크에서 배송지 입력, 가짜 링크)
-- =====================================================================

-- 정기배송: 회원이 상품 하나를 1·2·4주마다 받는다. 다음 배송 예정일 이틀 전에 시뮬레이터가 주문·결제(모의)한다
create table subscription (
    id                  bigserial    primary key,
    member_id           bigint       not null references member(id) on delete cascade,
    vendor_item_id      bigint       not null references vendor_item(id),
    product_id          bigint       not null references product(id),
    quantity            int          not null check (quantity between 1 and 10),
    cycle_weeks         int          not null check (cycle_weeks in (1, 2, 4)),
    address_id          bigint       references address(id) on delete set null,  -- 지우면 기본 배송지로
    method              varchar(20)  not null,                                     -- 모의 결제수단 (YANGPAY/CARD)
    status              varchar(20)  not null default 'ACTIVE',                    -- ACTIVE / CANCELED
    next_delivery_date  date         not null,
    last_delivery_date  date,
    rounds              int          not null default 0,                           -- 주문까지 된 회차 수
    skipped             int          not null default 0,                           -- 건너뛴 회차 수 (직접·재고 부족)
    last_note           varchar(200),                                              -- 마지막 회차를 못 보낸 이유
    created_at          timestamptz  not null,
    canceled_at         timestamptz
);
create index idx_subscription_due on subscription(status, next_delivery_date);
create index idx_subscription_member on subscription(member_id);
-- 같은 상품 옵션은 진행 중인 정기배송 하나만
create unique index uq_subscription_active on subscription(member_id, vendor_item_id) where status = 'ACTIVE';

alter table orders add column subscription_id bigint references subscription(id) on delete set null;
create index idx_orders_subscription on orders(subscription_id) where subscription_id is not null;

-- 선물하기: 결제 후 받는 분이 링크에서 배송지를 입력해야 출고. 5일 안에 입력하지 않으면 자동 취소·환불
alter table orders add column gift_waiting boolean not null default false;

create table gift (
    id               bigserial    primary key,
    order_id         bigint       not null unique references orders(id) on delete cascade,
    token            varchar(40)  not null unique,
    sender_name      varchar(50)  not null,
    recipient_name   varchar(50)  not null,
    recipient_phone  varchar(20)  not null,
    message          varchar(200),
    status           varchar(20)  not null default 'WAITING',   -- WAITING / ACCEPTED / EXPIRED / CANCELED
    expires_at       timestamptz  not null,
    accepted_at      timestamptz,
    created_at       timestamptz  not null
);
create index idx_gift_waiting on gift(status, expires_at);
