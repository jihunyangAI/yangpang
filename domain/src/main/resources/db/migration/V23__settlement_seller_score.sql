-- =====================================================================
-- V23: M4 ⑥ 정산(주 단위, 모의 지급) · 판매자 점수(실적 기반 매일 계산) · 제재(경고·판매 정지) · 판매자 통계
-- =====================================================================

-- 판매자 점수 일별 기록: 점수 = 리뷰 평점 40 + 판매자 귀책 반품·교환 35 + 문의 답변 25
create table seller_score_daily (
    seller_id           bigint        not null references seller(id) on delete cascade,
    day                 date          not null,
    score               numeric(5,1)  not null,
    rating_avg          numeric(3,2),                 -- 최근 90일 리뷰 평균 (없으면 null)
    review_count        int           not null,
    delivered           int           not null,       -- 최근 30일 배송완료 상품 수
    seller_fault_claims int           not null,       -- 최근 30일 판매자 귀책 반품·교환 상품 수
    inquiries           int           not null,       -- 최근 30일 위너 상품 문의 수 (48시간 지난 것)
    answered_48h        int           not null,       -- 그중 이 판매자가 48시간 안에 답한 수
    primary key (seller_id, day)
);

-- 제재 이력: 경고·판매 정지·해제. 판매 정지 때 멈춘 판매 오퍼를 기록해 해제할 때 되살린다
create table seller_sanction (
    id          bigserial    primary key,
    seller_id   bigint       not null references seller(id) on delete cascade,
    type        varchar(20)  not null,     -- WARNING / SUSPEND / LIFT
    reason      varchar(300) not null,
    actor       varchar(20)  not null,     -- SYSTEM / ADMIN
    offer_ids   bigint[],
    created_at  timestamptz  not null
);
create index idx_seller_sanction_seller on seller_sanction(seller_id, created_at desc);

-- 정산: 판매자별 주(월~일, KST) 단위. 구매확정 상품 판매금액 - 수수료 - 광고비. 주가 끝나고 7일 뒤 모의 지급
create table settlement (
    id            bigserial    primary key,
    seller_id     bigint       not null references seller(id) on delete cascade,
    period_start  date         not null,
    period_end    date         not null,          -- 다음 주 월요일 (이 날 미포함)
    item_count    int          not null default 0,
    sales_amount  bigint       not null default 0,
    commission    bigint       not null default 0,
    ad_cost       bigint       not null default 0,
    payout        bigint       not null default 0,
    status        varchar(20)  not null default 'SCHEDULED',   -- SCHEDULED / PAID
    pay_date      date         not null,
    created_at    timestamptz  not null,
    paid_at       timestamptz,
    unique (seller_id, period_start)
);
create index idx_settlement_status on settlement(status, pay_date);

alter table order_item add column settlement_id bigint references settlement(id) on delete set null;
create index idx_order_item_unsettled on order_item(seller_id, confirmed_at) where status = 'CONFIRMED' and settlement_id is null;
