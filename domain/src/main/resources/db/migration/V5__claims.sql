-- =====================================================================
-- V5: 취소·반품·교환·환불 (기획서 5.10)
-- =====================================================================
create sequence claim_no_seq start with 1000001;

create table claim (
    id                   bigserial primary key,
    claim_no             varchar(30)  not null unique,
    order_id             bigint       not null references orders(id),
    member_id            bigint       not null references member(id),
    claim_type           varchar(20)  not null,   -- CANCEL / RETURN / EXCHANGE
    status               varchar(30)  not null,   -- REQUESTED / PICKING_UP / PICKED_UP / COMPLETED / REJECTED / WITHDRAWN
    reason_code          varchar(30)  not null,   -- CHANGE_OF_MIND / DEFECTIVE / WRONG_ITEM / DAMAGED / DELAYED / OTHER
    reason_detail        varchar(500),
    fault                varchar(20)  not null,   -- CUSTOMER / SELLER
    pickup_method        varchar(20),             -- DOOR(문 앞 회수) / CONVENIENCE(편의점 반품)
    return_shipping_fee  bigint       not null default 0,   -- 고객 부담 반품비 (환불액에서 차감)
    refund_amount        bigint       not null default 0,
    requested_at         timestamptz  not null,
    status_changed_at    timestamptz  not null,
    completed_at         timestamptz
);
create index idx_claim_member on claim(member_id, requested_at desc);
create index idx_claim_status on claim(status);

create table claim_item (
    id                       bigserial primary key,
    claim_id                 bigint not null references claim(id) on delete cascade,
    order_item_id            bigint not null references order_item(id),
    exchange_vendor_item_id  bigint references vendor_item(id),   -- 교환: 새로 받을 옵션
    refund_amount            bigint not null default 0
);
create index idx_claim_item_order_item on claim_item(order_item_id);

create table refund (
    id            bigserial primary key,
    claim_id      bigint      references claim(id),
    order_id      bigint      not null references orders(id),
    amount        bigint      not null,
    method        varchar(20) not null,   -- ORIGINAL(원결제수단, 모의) / CASH(양팡캐시)
    status        varchar(20) not null,   -- COMPLETED
    created_at    timestamptz not null
);
create index idx_refund_order on refund(order_id);

-- 주문에 환불 누계
alter table orders add column refunded_amount bigint not null default 0;
