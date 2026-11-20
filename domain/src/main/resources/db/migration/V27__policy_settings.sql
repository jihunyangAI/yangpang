-- =====================================================================
-- V27: 운영 기준값을 관리자 설정으로 (기획서 14장 리스크 "레퍼런스 정책 수치가 바뀜" 대응)
-- 기본값은 지금까지 코드에 있던 값 그대로. 바꾼 값은 새로 계산하는 것부터 적용 (이미 만든 주문·정산 금액은 그대로)
-- 운영 설정이라 가짜 데이터 초기화(시드)로 지우지 않는다
-- =====================================================================
create table policy_setting (
    key          varchar(50)   primary key,
    value        bigint        not null,
    category     varchar(20)   not null,
    label        varchar(60)   not null,
    unit         varchar(10)   not null,     -- 원 / % / 일 / 분 / 점
    min_value    bigint        not null,
    max_value    bigint        not null,
    description  varchar(300)  not null,
    sort_order   int           not null,
    updated_at   timestamptz   not null default now(),
    updated_by   varchar(100),
    check (value between min_value and max_value)
);

create table policy_setting_history (
    id          bigserial     primary key,
    key         varchar(50)   not null references policy_setting(key) on delete cascade,
    old_value   bigint        not null,
    new_value   bigint        not null,
    reason      varchar(200)  not null,
    changed_by  varchar(100)  not null,
    changed_at  timestamptz   not null
);
create index idx_policy_history on policy_setting_history(changed_at desc);

insert into policy_setting(key, value, category, label, unit, min_value, max_value, description, sort_order) values
('SHIPPING_FREE_THRESHOLD',   19800, '배송', '무료배송 기준 금액',          '원', 0, 200000, '양팡배송·프레시·판매자양팡 묶음(그리고 양팡직구 묶음)이 할인 후 이 금액 이상이면 배송비 무료', 10),
('SHIPPING_FEE',               3000, '배송', '양팡배송 배송비',             '원', 0, 30000,  '무료배송 기준 미만일 때 양팡 물류 묶음 배송비. 고객 귀책 반품비도 이 금액 (플러스 회원은 무료)', 20),
('GLOBAL_SHIPPING_FEE',        3000, '배송', '양팡직구 배송비',             '원', 0, 30000,  '무료배송 기준 미만일 때 양팡직구 묶음 배송비', 30),
('GLOBAL_RETURN_FEE',         15000, '배송', '양팡직구 해외 반송비',        '원', 0, 100000, '양팡직구 상품 단순 변심 반품·교환 시 고객 부담 (플러스도 부담)', 40),
('RETURN_WINDOW_DAYS',            7, '취소·반품', '반품·교환 신청 기간',     '일', 1, 60,     '배송 완료 후 이 기간 안에 반품·교환 신청', 10),
('PLUS_RETURN_WINDOW_DAYS',      30, '취소·반품', '플러스 반품 신청 기간',   '일', 1, 90,     '양팡플러스 회원의 양팡 물류 상품 반품·교환 신청 기간', 20),
('PAYMENT_TIMEOUT_MINUTES',      10, '주문·결제', '결제 대기 시간',          '분', 1, 120,    '주문서를 만들고 이 시간 안에 결제하지 않으면 자동 취소·재고 복구', 10),
('GIFT_ACCEPT_DAYS',              5, '주문·결제', '선물 배송지 입력 기한',   '일', 1, 30,     '결제 후 받는 분이 이 기간 안에 배송지를 넣지 않으면 자동 취소·환불', 20),
('PURCHASE_CASH_PERCENT',         1, '혜택', '구매확정 캐시 적립률',        '%', 0, 10,     '양페이로 결제한 상품을 구매확정하면 결제 금액의 이 비율을 양팡캐시로 적립', 10),
('DELAY_COMPENSATION_CASH',    1000, '혜택', '배송 지연 보상 캐시',         '원', 0, 10000,  '도착 보장일보다 늦게 도착한 상품마다 지급하는 양팡캐시', 20),
('PLUS_MONTHLY_FEE',           7890, '혜택', '양팡플러스 월 회비',          '원', 0, 50000,  '새로 가입하거나 다시 시작할 때부터 적용 (진행 중인 기간의 청구액은 그대로)', 30),
('SUBSCRIPTION_DISCOUNT_PERCENT', 5, '혜택', '정기배송 할인율',             '%', 0, 30,     '정기배송 회차 주문의 상품 금액 할인 (10원 단위 내림)', 40),
('PARTNERS_RATE_PERCENT',         3, '혜택', '파트너스 적립률',             '%', 0, 20,     '파트너스 링크로 들어온 주문 금액(할인 후) 중 링크 주인의 예정 실적 비율', 50),
('SELLER_COMMISSION_PERCENT',    10, '판매자', '판매 수수료',               '%', 0, 50,     '정산 때 구매확정 판매금액에서 빼는 수수료', 10),
('FULFILLMENT_FEE_PERCENT',       5, '판매자', '물류 대행비 (판매자양팡)',   '%', 0, 30,     '판매자양팡 상품에 수수료에 더해 빼는 비율', 20),
('SETTLEMENT_PAY_AFTER_DAYS',     7, '판매자', '정산 지급일',               '일', 0, 60,     '정산 주가 끝난 다음 월요일부터 이 기간 뒤 지급 (새로 만드는 정산부터)', 30),
('SELLER_WARNING_SCORE',         40, '판매자', '자동 경고 점수',            '점', 0, 100,    '판매자 점수가 이 점수 미만이면 시스템 경고 (7일에 한 번)', 40);
