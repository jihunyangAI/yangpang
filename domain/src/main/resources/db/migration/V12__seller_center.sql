-- =====================================================================
-- V12: 셀러센터 · 아이템 위너 · 카테고리 필수 속성 (기획서 5.15, 8.5)
-- =====================================================================

-- 판매자 입점 상태 (기존 판매자는 승인됨)
alter table seller add column status varchar(20) not null default 'ACTIVE';   -- PENDING / ACTIVE / REJECTED

-- 판매자 계정 (회원 ↔ 판매자, 한 판매자에 여러 담당자 가능)
create table seller_member (
    seller_id  bigint not null references seller(id) on delete cascade,
    member_id  bigint not null references member(id) on delete cascade,
    primary key (seller_id, member_id)
);
create unique index ux_seller_member_member on seller_member(member_id);

-- 카테고리별 필수 속성 (대분류에 정의하면 하위 소분류 상품 전체에 적용)
create table category_attribute (
    id           bigserial primary key,
    category_id  bigint       not null references category(id) on delete cascade,
    name         varchar(30)  not null,
    required     boolean      not null default true,
    options      varchar(300),            -- 쉼표 구분 선택지 (null이면 자유 입력)
    sort_order   int          not null default 0
);
create index idx_category_attribute_category on category_attribute(category_id);

create table product_attribute (
    product_id  bigint       not null references product(id) on delete cascade,
    name        varchar(30)  not null,
    value       varchar(100) not null,
    primary key (product_id, name)
);

-- 송장 (판매자 출고 처리)
alter table order_item
    add column carrier      varchar(30),
    add column tracking_no  varchar(40);
