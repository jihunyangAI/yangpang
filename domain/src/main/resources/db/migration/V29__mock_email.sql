-- =====================================================================
-- V29: 이메일 알림 (모의, 기획서 §20 "알림함 + 이메일 모의"). 실제로 보내지 않고 보낸 것처럼 기록만 남긴다
-- 주문·배송·취소/반품 알림은 이메일 수신 설정을 켠 회원에게, 혜택·찜 알림은 거기에 마케팅 수신 동의까지 한 회원에게만
-- =====================================================================
alter table member add column email_notify boolean not null default true;

create table mock_email (
    id          bigserial     primary key,
    member_id   bigint        not null references member(id) on delete cascade,
    to_email    varchar(100)  not null,
    category    varchar(20)   not null,       -- 알림 종류 (ORDER / DELIVERY / CLAIM / BENEFIT / PRICE_DROP / RESTOCK)
    subject     varchar(200)  not null,
    body        varchar(2000) not null,
    link        varchar(300),
    created_at  timestamptz   not null,
    read_at     timestamptz
);
create index idx_mock_email_member on mock_email(member_id, created_at desc);
create index idx_mock_email_created on mock_email(created_at desc);
