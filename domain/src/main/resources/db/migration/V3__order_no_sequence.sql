-- =====================================================================
-- V3: 주문번호 시퀀스
-- 주문번호 형식: Y + yyMMdd + 8자리 일련번호 (예: Y26112010000001)
-- 가짜 데이터(seed)는 6자리(주문 id)를 쓰므로 길이가 달라 겹치지 않는다.
-- =====================================================================
create sequence order_no_seq start with 10000001;

-- 무통장(가상계좌) 모의 결제 입금 기한
alter table mock_payment add column deposit_deadline timestamptz;
alter table mock_payment add column virtual_account varchar(40);
