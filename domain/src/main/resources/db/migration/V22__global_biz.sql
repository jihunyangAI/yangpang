-- =====================================================================
-- V22: M4 ⑤ 양팡직구(해외 배송·개인통관고유부호) · 비즈(사업자 회원가·세금계산서 요청, 모의)
-- =====================================================================

-- 직구: 주문에 개인통관고유부호(가짜, P + 숫자 12자리). 회원에게도 저장해 다음 주문·정기배송에 쓴다
alter table orders add column customs_code varchar(13);
alter table member add column customs_code varchar(13);

-- 비즈: 사업자 회원가 (비즈 회원에게만, 없으면 일반 가격)
alter table vendor_item add column biz_price bigint check (biz_price is null or biz_price > 0);

-- 세금계산서 요청 (모의 발행: 실제 국세청 전송 없음)
create table tax_invoice (
    id            bigserial    primary key,
    order_id      bigint       not null unique references orders(id) on delete cascade,
    member_id     bigint       not null references member(id) on delete cascade,
    business_no   varchar(12)  not null,
    company_name  varchar(50)  not null,
    ceo_name      varchar(30)  not null,
    email         varchar(100) not null,
    amount        bigint       not null,
    status        varchar(20)  not null default 'REQUESTED',   -- REQUESTED / ISSUED
    invoice_no    varchar(30),
    requested_at  timestamptz  not null,
    issued_at     timestamptz
);
create index idx_tax_invoice_member on tax_invoice(member_id, requested_at desc);
create index idx_tax_invoice_status on tax_invoice(status, requested_at);
