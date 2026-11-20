-- =====================================================================
-- V16: 양팡 랭킹 (기획서 5.18) — 여러 지표의 가중합. 가중치는 관리자에서 조정
-- =====================================================================
alter table product
    add column ranking_score       numeric(6, 2) not null default 0,
    add column ranking_updated_at  timestamptz;
create index idx_product_ranking on product(ranking_score desc) where status = 'ON_SALE';

-- 상품 상세 조회 수 (일별). 클릭·전환율 계산용
create table product_view_daily (
    product_id  bigint not null references product(id) on delete cascade,
    day         date   not null,
    views       int    not null default 0,
    primary key (product_id, day)
);

create table ranking_weight (
    factor      varchar(20)  primary key,
    label       varchar(50)  not null,
    description varchar(200) not null,
    weight      int          not null check (weight between 0 and 100),
    sort_order  int          not null
);
insert into ranking_weight(factor, label, description, weight, sort_order) values
    ('SALES',      '판매량',     '누적 판매량 + 최근 30일 판매량 x3',                  30, 1),
    ('VIEWS',      '클릭(조회)', '최근 30일 상품 상세 조회 수',                          10, 2),
    ('CONVERSION', '전환율',     '최근 30일 주문 수 ÷ 조회 수 (적은 표본은 보정)',        15, 3),
    ('REVIEW',     '리뷰',       '리뷰 수와 평점 (리뷰가 적으면 평균 4.0 쪽으로 보정)', 15, 4),
    ('PRICE',      '가격',       '같은 소분류 중간 가격보다 쌀수록 높음',               10, 5),
    ('DELIVERY',   '배송 속도',  '양팡배송·프레시 100, 판매자양팡 80, 판매자배송 40',   15, 6),
    ('RETURN',     '반품률',     '반품된 비율이 낮을수록 높음',                          5, 7);
