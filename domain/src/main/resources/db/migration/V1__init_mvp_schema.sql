-- =====================================================================
-- Yangpang V1: MVP(M0~M1) 스키마
-- 기획서 yangpang.md 9장 / reference/coupang.md 23장 기준
-- 금액은 모두 원 단위 정수(bigint), 시각은 timestamptz
-- =====================================================================

-- ---------- 회원 ----------
create table member (
    id               bigserial primary key,
    email            varchar(255) not null unique,
    password_hash    varchar(255) not null,
    name             varchar(50)  not null,
    phone            varchar(20)  not null,
    birth_date       date,
    grade            varchar(20)  not null default 'NORMAL',   -- NORMAL / PLUS / BIZ
    role             varchar(20)  not null default 'USER',     -- USER / SELLER / ADMIN
    status           varchar(20)  not null default 'ACTIVE',   -- ACTIVE / DORMANT / WITHDRAWN
    marketing_agreed boolean      not null default false,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now()
);

create table address (
    id             bigserial primary key,
    member_id      bigint       not null references member(id) on delete cascade,
    label          varchar(30),                     -- 집, 회사 등
    recipient      varchar(50)  not null,
    phone          varchar(20)  not null,
    zipcode        varchar(10)  not null,
    address1       varchar(255) not null,           -- 도로명 주소
    address2       varchar(255),                    -- 상세 주소
    region         varchar(20)  not null,           -- METRO(수도권) / LOCAL(지방) / REMOTE(도서산간)
    entrance_type  varchar(20)  not null default 'FREE',  -- FREE(자유출입) / PASSWORD / CALL(호출) / ETC
    entrance_note  varchar(100),
    request_note   varchar(100),                    -- 문 앞, 경비실 등
    is_default     boolean      not null default false,
    created_at     timestamptz  not null default now()
);
create index idx_address_member on address(member_id);

-- ---------- 카테고리 / 브랜드 / 판매자 ----------
create table category (
    id          bigserial primary key,
    parent_id   bigint references category(id),
    name        varchar(50) not null,
    depth       int         not null,              -- 1=대분류
    sort_order  int         not null default 0,
    icon        varchar(50)
);
create index idx_category_parent on category(parent_id);

create table brand (
    id    bigserial primary key,
    name  varchar(100) not null unique
);

create table seller (
    id                      bigserial primary key,
    name                    varchar(100) not null,
    seller_type             varchar(20)  not null,   -- DIRECT(양팡 직매입) / FULFILLMENT(판매자양팡) / MARKETPLACE(판매자배송)
    business_no             varchar(20)  not null,
    ceo_name                varchar(50)  not null,
    business_address        varchar(255) not null,
    cs_phone                varchar(20)  not null,
    shipping_fee            bigint       not null default 3000,  -- 판매자배송 기본 배송비
    free_shipping_threshold bigint,                              -- 판매자배송 조건부 무료 기준 (null=항상 유료)
    score                   int          not null default 100,
    created_at              timestamptz  not null default now()
);

-- ---------- 상품 (Product > Item(SKU) > VendorItem(판매 오퍼)) ----------
create table product (
    id               bigserial primary key,
    category_id      bigint       not null references category(id),
    brand_id         bigint       references brand(id),
    name             varchar(255) not null,
    option1_name     varchar(30),                  -- 예: 색상
    option2_name     varchar(30),                  -- 예: 사이즈
    description_html text,
    notice_info      text,                         -- 상품정보제공고시 (JSON 문자열)
    unit_label       varchar(20),                  -- 단위가격 표시용 (예: 100g)
    unit_quantity    int,                          -- 상품 1개가 unit_label의 몇 배인지
    rating_avg       numeric(2,1) not null default 0,
    review_count     int          not null default 0,
    sales_count      int          not null default 0,
    status           varchar(20)  not null default 'ON_SALE', -- ON_SALE / STOPPED
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now()
);
create index idx_product_category on product(category_id);
create index idx_product_brand on product(brand_id);

create table product_image (
    id          bigserial primary key,
    product_id  bigint       not null references product(id) on delete cascade,
    url         varchar(500) not null,
    sort_order  int          not null default 0
);
create index idx_product_image_product on product_image(product_id);

create table item (
    id             bigserial primary key,
    product_id     bigint      not null references product(id) on delete cascade,
    option1_value  varchar(50),
    option2_value  varchar(50),
    barcode        varchar(30),
    sort_order     int         not null default 0
);
create index idx_item_product on item(product_id);

create table vendor_item (
    id             bigserial primary key,
    item_id        bigint      not null references item(id) on delete cascade,
    seller_id      bigint      not null references seller(id),
    list_price     bigint      not null,            -- 정가
    sale_price     bigint      not null,            -- 판매가
    plus_price     bigint,                          -- 양팡플러스 회원가 (null=없음)
    stock          int         not null default 0,
    delivery_type  varchar(20) not null,            -- YANGPANG / FRESH / FULFILLMENT / SELLER (이후 GLOBAL, INSTALL)
    max_per_order  int         not null default 99,
    is_winner      boolean     not null default true,
    status         varchar(20) not null default 'ON_SALE',
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    unique (item_id, seller_id)
);
create index idx_vendor_item_item on vendor_item(item_id);
create index idx_vendor_item_seller on vendor_item(seller_id);

-- ---------- 장바구니 ----------
create table cart_item (
    id              bigserial primary key,
    member_id       bigint      not null references member(id) on delete cascade,
    vendor_item_id  bigint      not null references vendor_item(id) on delete cascade,
    quantity        int         not null,
    selected        boolean     not null default true,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    unique (member_id, vendor_item_id)
);

-- ---------- 주문 ----------
create table orders (
    id                    bigserial primary key,
    order_no              varchar(30) not null unique,
    member_id             bigint      not null references member(id),
    status                varchar(30) not null,      -- 8.4 주문 상태 (주문 단위 요약)
    -- 배송지 스냅샷
    recipient             varchar(50)  not null,
    recipient_phone       varchar(20)  not null,
    zipcode               varchar(10)  not null,
    address1              varchar(255) not null,
    address2              varchar(255),
    region                varchar(20)  not null,
    entrance_type         varchar(20)  not null,
    entrance_note         varchar(100),
    request_note          varchar(100),
    -- 금액
    total_product_amount  bigint      not null,      -- 판매가 합계
    discount_amount       bigint      not null default 0,
    shipping_fee          bigint      not null default 0,
    paid_amount           bigint      not null,      -- 최종 결제금액
    ordered_at            timestamptz not null default now(),
    paid_at               timestamptz
);
create index idx_orders_member on orders(member_id, ordered_at desc);

create table order_item (
    id               bigserial primary key,
    order_id         bigint       not null references orders(id) on delete cascade,
    vendor_item_id   bigint       not null references vendor_item(id),
    product_id       bigint       not null references product(id),
    seller_id        bigint       not null references seller(id),
    -- 상품 스냅샷
    product_name     varchar(255) not null,
    option_text      varchar(120),
    image_url        varchar(500),
    delivery_type    varchar(20)  not null,
    -- 금액 (할인은 상품별 안분 저장)
    unit_price       bigint       not null,
    quantity         int          not null,
    discount_amount  bigint       not null default 0,
    line_amount      bigint       not null,           -- unit_price * quantity - discount_amount
    status           varchar(30)  not null,
    promised_date    date,
    delivered_at     timestamptz,
    confirmed_at     timestamptz
);
create index idx_order_item_order on order_item(order_id);

create table mock_payment (
    id           bigserial primary key,
    order_id     bigint      not null references orders(id) on delete cascade,
    method       varchar(20) not null,              -- YANGPAY / CARD / PHONE / BANK (모두 모의)
    amount       bigint      not null,
    status       varchar(20) not null,              -- APPROVED / FAILED / PENDING
    approved_at  timestamptz,
    created_at   timestamptz not null default now()
);

create table order_event (
    id             bigserial primary key,
    order_id       bigint      not null references orders(id) on delete cascade,
    order_item_id  bigint      references order_item(id) on delete cascade,
    from_status    varchar(30),
    to_status      varchar(30) not null,
    actor          varchar(30) not null,             -- MEMBER / SELLER / ADMIN / SYSTEM / SEED
    note           varchar(255),
    created_at     timestamptz not null default now()
);
create index idx_order_event_order on order_event(order_id);
