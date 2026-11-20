-- =====================================================================
-- V20: M4 ③ 광고(검색 AD, 키워드 CPC 입찰, 모의 과금) · 파트너스(공유 링크 실적, 모의)
-- =====================================================================

-- 광고 캠페인: 판매자 1명이 자기가 파는 상품 1개를 광고. 하루 예산을 다 쓰면 그날은 노출하지 않음
create table ad_campaign (
    id            bigserial    primary key,
    seller_id     bigint       not null references seller(id) on delete cascade,
    product_id    bigint       not null references product(id) on delete cascade,
    daily_budget  bigint       not null check (daily_budget >= 1000),
    status        varchar(20)  not null default 'ACTIVE',   -- ACTIVE / PAUSED
    created_at    timestamptz  not null
);
create index idx_ad_campaign_seller on ad_campaign(seller_id);

-- 키워드별 클릭당 입찰가 (검색어와 정확히 같을 때 노출)
create table ad_keyword (
    id           bigserial   primary key,
    campaign_id  bigint      not null references ad_campaign(id) on delete cascade,
    keyword      varchar(50) not null,
    bid          int         not null check (bid between 100 and 10000),
    unique (campaign_id, keyword)
);
create index idx_ad_keyword_keyword on ad_keyword(keyword);

-- 일별 성과 (노출·클릭·비용·전환·전환 매출)
create table ad_stat_daily (
    campaign_id  bigint not null references ad_campaign(id) on delete cascade,
    keyword_id   bigint not null references ad_keyword(id) on delete cascade,
    day          date   not null,
    impressions  int    not null default 0,
    clicks       int    not null default 0,
    cost         bigint not null default 0,
    conversions  int    not null default 0,
    sales        bigint not null default 0,
    primary key (campaign_id, keyword_id, day)
);

-- 클릭 (같은 노출에서 여러 번 눌러도 한 번만 과금: token = 노출 서명)
create table ad_click (
    id           bigserial    primary key,
    token        varchar(40)  not null unique,
    campaign_id  bigint       not null references ad_campaign(id) on delete cascade,
    keyword_id   bigint       not null references ad_keyword(id) on delete cascade,
    product_id   bigint       not null references product(id) on delete cascade,
    member_id    bigint       references member(id) on delete set null,
    cost         int          not null,
    order_id     bigint       references orders(id) on delete set null,   -- 24시간 안에 산 주문 (전환)
    created_at   timestamptz  not null
);
create index idx_ad_click_member on ad_click(member_id, product_id, created_at desc);

-- 파트너스: 회원이 상품 공유 링크를 만들고, 그 링크로 들어온 사람이 24시간 안에 산 금액의 3%를 실적으로 (모의, 지급 없음)
create table affiliate_link (
    id          bigserial    primary key,
    member_id   bigint       not null references member(id) on delete cascade,
    product_id  bigint       not null references product(id) on delete cascade,
    code        varchar(12)  not null unique,
    clicks      int          not null default 0,
    created_at  timestamptz  not null,
    unique (member_id, product_id)
);

alter table orders add column affiliate_link_id bigint references affiliate_link(id) on delete set null;

create table affiliate_earning (
    id           bigserial    primary key,
    link_id      bigint       not null references affiliate_link(id) on delete cascade,
    order_id     bigint       not null unique references orders(id) on delete cascade,
    base_amount  bigint       not null,
    amount       bigint       not null,
    status       varchar(20)  not null,   -- PENDING(구매확정 전) / CONFIRMED / CANCELED(전부 취소·반품)
    created_at   timestamptz  not null,
    decided_at   timestamptz
);
create index idx_affiliate_earning_pending on affiliate_earning(status) where status = 'PENDING';
