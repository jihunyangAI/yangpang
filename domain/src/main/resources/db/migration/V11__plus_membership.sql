-- =====================================================================
-- V11: 양팡플러스 멤버십 (기획서 5.8, 레퍼런스 13장)
-- =====================================================================
create table membership (
    id                    bigserial primary key,
    member_id             bigint      not null unique references member(id) on delete cascade,
    status                varchar(20) not null,     -- ACTIVE / CANCELED(기간 끝까지 이용) / ENDED
    monthly_fee           bigint      not null,
    started_at            timestamptz not null,
    current_period_start  timestamptz not null,
    current_period_end    timestamptz not null,     -- 다음 결제(갱신) 시각
    canceled_at           timestamptz,
    ended_at              timestamptz,
    trial_used            boolean     not null default true
);
create index idx_membership_period on membership(status, current_period_end);

-- 모의 청구 기록 (실제 결제 없음)
create table membership_billing (
    id             bigserial primary key,
    membership_id  bigint      not null references membership(id) on delete cascade,
    amount         bigint      not null,
    kind           varchar(20) not null,   -- FREE_TRIAL / MONTHLY
    period_start   timestamptz not null,
    period_end     timestamptz not null,
    billed_at      timestamptz not null
);

-- 상품 목록용 대표 회원가
alter table product add column rep_plus_price bigint;

-- 주문: 플러스 회원이라 아낀 금액 (회원가 차이 + 무료배송) → 마이양팡 "이번 달 절약"
alter table order_item add column plus_saving bigint not null default 0;
alter table orders add column shipping_saved bigint not null default 0;
