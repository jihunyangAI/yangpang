-- =====================================================================
-- V31: 성인 인증 (기획서 §12, 모의). 19세 이상 상품은 성인 인증(1년 유효)을 한 회원만 보고 살 수 있다
-- =====================================================================
alter table product add column adult boolean not null default false;
alter table member add column adult_verified_at timestamptz;
