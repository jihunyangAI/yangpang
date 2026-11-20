-- =====================================================================
-- V7: 쿠폰 · 양팡캐시 (기획서 5.7, 8.1, 15장)
-- =====================================================================
create table coupon (
    id                  bigserial primary key,
    name                varchar(100) not null,
    coupon_type         varchar(20)  not null,   -- PRODUCT(상품 쿠폰) / CART(장바구니 쿠폰)
    discount_type       varchar(20)  not null,   -- AMOUNT(정액) / RATE(정률 %)
    discount_value      bigint       not null,
    max_discount        bigint,                  -- 정률 할인 최대 금액
    min_order_amount    bigint       not null default 0,
    target_category_id  bigint       references category(id),   -- null이면 전체 (하위 카테고리 포함)
    target_product_id   bigint       references product(id),
    valid_days          int          not null default 30,       -- 받은 날부터 사용 가능 기간
    ends_at             timestamptz,                             -- 쿠폰 배포 종료
    total_quantity      int,                                     -- null이면 무제한
    issued_count        int          not null default 0,
    downloadable        boolean      not null default true,      -- 쿠폰존·상품 상세에서 받기
    auto_issue          varchar(20),                             -- SIGNUP(가입 시 자동 지급)
    active              boolean      not null default true,
    created_at          timestamptz  not null default now()
);

create table member_coupon (
    id              bigserial primary key,
    member_id       bigint      not null references member(id),
    coupon_id       bigint      not null references coupon(id),
    status          varchar(20) not null,   -- AVAILABLE / USED / EXPIRED
    issued_at       timestamptz not null,
    expires_at      timestamptz not null,
    used_at         timestamptz,
    used_order_id   bigint      references orders(id),
    unique (member_id, coupon_id)
);
create index idx_member_coupon_member on member_coupon(member_id, status);

-- 양팡캐시 원장: 잔액 = amount 합계
create table cash_ledger (
    id           bigserial primary key,
    member_id    bigint       not null references member(id),
    amount       bigint       not null,           -- + 적립·환불 / - 사용·소멸
    entry_type   varchar(30)  not null,           -- EARN_PURCHASE / EARN_REVIEW / EARN_DELAY / EARN_EVENT / USE / USE_CANCEL / REFUND
    order_id     bigint       references orders(id),
    description  varchar(200) not null,
    expires_at   timestamptz,
    created_at   timestamptz  not null
);
create index idx_cash_member on cash_ledger(member_id, created_at desc);

-- 주문: 쿠폰·캐시
alter table orders
    add column coupon_discount   bigint not null default 0,
    add column cash_used         bigint not null default 0,
    add column cart_coupon_id    bigint references member_coupon(id);

-- 주문 상품: 할인(discount_amount)은 상품 쿠폰 + 장바구니 쿠폰 안분, 캐시는 별도로 안분
alter table order_item
    add column product_coupon_id  bigint references member_coupon(id),
    add column cash_amount        bigint not null default 0;
