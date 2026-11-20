-- =====================================================================
-- V10: 메인 배너 (기획서 D-03, 5.16 전시 관리)
-- =====================================================================
create table banner (
    id          bigserial primary key,
    title       varchar(60)  not null,
    subtitle    varchar(100),
    caption     varchar(40),                 -- 제목 위 작은 문구
    link_url    varchar(300) not null,
    theme       varchar(20)  not null,       -- PLUS / SALE / FRESH / BRAND / DARK (배경 색 조합)
    sort_order  int          not null default 0,
    active      boolean      not null default true,
    starts_at   timestamptz,
    ends_at     timestamptz,
    created_at  timestamptz  not null default now()
);

insert into banner(title, subtitle, caption, link_url, theme, sort_order) values
  ('금액 상관없이 무료배송', '30일 무료반품 · 회원 전용가 · 첫 달 무료', '양팡플러스 (데모)', '/plus', 'PLUS', 1),
  ('오늘의 특가', '한정 수량 · 놓치면 내일 다시', '매일 오전 7시 오픈', '/deals', 'SALE', 2),
  ('밤 12시까지 주문하면 새벽 도착', '신선식품 새벽배송', '양팡프레시', '/fresh', 'FRESH', 3);
