-- =====================================================================
-- V26: 고객센터 (기획서 §20) — 자주 묻는 질문(FAQ)·1:1 문의. 챗봇은 M4 ⑧
-- FAQ는 운영 콘텐츠라 가짜 데이터 초기화(시드)로 지우지 않는다
-- =====================================================================
create table faq (
    id          bigserial    primary key,
    category    varchar(20)  not null,     -- ORDER / DELIVERY / CLAIM / PLUS / BENEFIT / ACCOUNT / SELLER
    question    varchar(200) not null,
    answer      text         not null,
    sort_order  int          not null default 0,
    view_count  int          not null default 0,
    created_at  timestamptz  not null default now()
);
create index idx_faq_category on faq(category, sort_order);

create table cs_inquiry (
    id           bigserial     primary key,
    member_id    bigint        not null references member(id) on delete cascade,
    category     varchar(20)   not null,
    order_id     bigint        references orders(id) on delete set null,
    title        varchar(60)   not null,
    content      varchar(2000) not null,
    status       varchar(20)   not null default 'WAITING',   -- WAITING / ANSWERED
    answer       varchar(2000),
    answered_at  timestamptz,
    created_at   timestamptz   not null
);
create index idx_cs_inquiry_member on cs_inquiry(member_id, created_at desc);
create index idx_cs_inquiry_status on cs_inquiry(status, created_at);

insert into faq(category, question, answer, sort_order) values
('ORDER', '주문을 취소하고 싶어요', '상품이 출고되기 전(결제완료·상품준비중)에는 마이양팡 > 주문목록 > 주문 상세에서 바로 취소할 수 있어요. 결제한 금액은 바로 환불돼요(연구용 모의 환불). 이미 출고됐다면 배송 완료 후 반품을 신청해 주세요.', 1),
('ORDER', '무통장입금(가상계좌)은 언제까지 입금해야 하나요?', '가상계좌를 받은 뒤 24시간 안에 입금해야 해요. 기한이 지나면 주문이 자동으로 취소되고 재고가 풀려요. 연구용이라 실제 계좌가 아니고, 입금은 시뮬레이터가 대신 처리해요.', 2),
('ORDER', '결제 화면에서 시간이 지나면 어떻게 되나요?', '주문서를 만들고 10분 안에 결제하지 않으면 주문이 자동으로 취소되고 쓴 쿠폰·캐시도 돌려드려요.', 3),
('ORDER', '선물하기는 어떻게 하나요?', '주문서에서 "선물하기"를 고르고 받는 분 이름과 휴대폰 번호만 입력하세요. 결제가 끝나면 선물 링크가 만들어지고, 받는 분이 링크에서 배송지를 입력하면 출고돼요. 5일 안에 입력하지 않으면 자동 취소·환불돼요.', 4),
('DELIVERY', '배송비는 얼마인가요?', '양팡배송·양팡프레시·판매자양팡 상품은 한 묶음으로 계산해 19,800원 이상이면 무료, 미만이면 3,000원이에요. 양팡플러스 회원은 항상 무료예요. 판매자배송 상품은 판매자마다 배송비 기준이 달라요.', 1),
('DELIVERY', '도착 보장일보다 늦게 오면 어떻게 되나요?', '양팡배송 상품이 도착 보장일보다 늦게 도착하면 양팡캐시로 보상해 드려요(모의).', 2),
('DELIVERY', '배송 조회는 어디서 하나요?', '마이양팡 > 주문목록에서 주문을 누르면 단계별 배송 상태와 도착 예정일, 배송 완료 사진을 볼 수 있어요.', 3),
('DELIVERY', '양팡직구 상품은 무엇이 다른가요?', '해외에서 보내는 상품이라 약 4일 걸리고, 주문할 때 개인통관고유부호(P로 시작하는 13자리)가 필요해요. 단순 변심 반품은 해외 반송비 15,000원이 들어요.', 4),
('CLAIM', '반품·교환은 언제까지 신청할 수 있나요?', '배송 완료 후 7일 안에 마이양팡 > 주문 상세에서 신청할 수 있어요(양팡플러스 회원은 30일). 신선식품은 반품할 수 없어요.', 1),
('CLAIM', '반품비는 누가 내나요?', '단순 변심·옵션 잘못 선택은 고객 부담(양팡배송 3,000원, 플러스 회원 무료, 판매자배송은 판매자 배송비)이고, 상품 불량·파손·오배송·배송 지연은 무료예요.', 2),
('CLAIM', '환불은 언제 되나요?', '취소는 바로, 반품은 상품이 회수되고 검수가 끝나면 환불돼요. 양팡캐시로 낸 부분은 캐시로, 나머지는 원래 결제수단으로 돌려드려요(모의).', 3),
('PLUS', '양팡플러스 혜택이 궁금해요', '월 7,890원(첫 달 무료, 모의 결제)으로 양팡배송 무료, 회원가, 30일 무료 반품을 받을 수 있어요. 가입·해지는 양팡플러스 화면에서 할 수 있어요.', 1),
('PLUS', '정기배송은 어떻게 신청하나요?', '식품·생활용품·뷰티 등 상품 상세에서 "정기배송"을 고르고 주기(1·2·4주)와 수량을 정하면 돼요. 상품 금액 5%를 할인해 드리고, 마이양팡 > 정기배송에서 주기 변경·건너뛰기·해지를 할 수 있어요.', 2),
('BENEFIT', '쿠폰은 어떻게 받고 쓰나요?', '쿠폰존이나 상품 상세에서 쿠폰을 받으면 주문서에서 가장 유리한 쿠폰이 자동으로 골라져요. 직접 바꿀 수도 있어요.', 1),
('BENEFIT', '양팡캐시는 어떻게 적립되나요?', '양페이로 결제한 상품을 구매확정하면 결제 금액의 1%가 적립돼요. 주문서에서 현금처럼 쓸 수 있고, 유효기간이 지나면 사라져요.', 2),
('ACCOUNT', '아이디(이메일)나 비밀번호를 잊어버렸어요', '로그인 화면의 "아이디·비밀번호 찾기"에서 가입할 때 쓴 휴대폰 번호로 인증하면 이메일을 확인하거나 비밀번호를 새로 정할 수 있어요(모의 인증).', 1),
('ACCOUNT', '회원 정보는 어디서 바꾸나요?', '마이양팡 > 회원정보에서 이름·마케팅 수신 동의·비밀번호를 바꿀 수 있어요.', 2),
('SELLER', '판매자로 입점하고 싶어요', '셀러센터에서 입점을 신청하면 운영자가 심사 후 승인해요. 승인되면 상품 등록, 주문 처리, 광고, 정산을 셀러센터에서 할 수 있어요(연구용 모의).', 1);
