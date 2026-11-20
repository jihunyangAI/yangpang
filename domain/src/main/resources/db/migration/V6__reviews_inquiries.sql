-- =====================================================================
-- V6: 리뷰(상품평) · 상품문의 (기획서 5.11)
-- =====================================================================
create table review (
    id                bigserial primary key,
    product_id        bigint       not null references product(id) on delete cascade,
    order_item_id     bigint       unique references order_item(id),   -- 구매 인증. 가짜 과거 리뷰는 null
    member_id         bigint       not null references member(id),
    rating            smallint     not null check (rating between 1 and 5),
    headline          varchar(60),
    content           text         not null,
    survey            text,                                   -- 맞춤 평가 JSON (예: {"사이즈":"잘 맞아요","배송":"빨라요"})
    option_text       varchar(120),
    photo_count       int          not null default 0,       -- 가짜 사진 장수 (/images/reviews/{id}/{n}.svg)
    helpful_count     int          not null default 0,
    report_count      int          not null default 0,
    sponsored         boolean      not null default false,   -- 체험단 리뷰 표시
    seller_reply      text,
    seller_replied_at timestamptz,
    status            varchar(20)  not null default 'VISIBLE', -- VISIBLE / HIDDEN
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null
);
create index idx_review_product_created on review(product_id, created_at desc);
create index idx_review_product_helpful on review(product_id, helpful_count desc);
create index idx_review_member on review(member_id);

create table review_helpful (
    review_id  bigint not null references review(id) on delete cascade,
    member_id  bigint not null references member(id),
    created_at timestamptz not null default now(),
    primary key (review_id, member_id)
);

create table product_inquiry (
    id            bigserial primary key,
    product_id    bigint       not null references product(id) on delete cascade,
    member_id     bigint       not null references member(id),
    inquiry_type  varchar(20)  not null,   -- PRODUCT / DELIVERY / RETURN / ETC
    content       varchar(1000) not null,
    secret        boolean      not null default false,
    answer        varchar(2000),
    answered_at   timestamptz,
    created_at    timestamptz  not null
);
create index idx_inquiry_product on product_inquiry(product_id, created_at desc);
create index idx_inquiry_unanswered on product_inquiry(answered_at) where answer is null;
