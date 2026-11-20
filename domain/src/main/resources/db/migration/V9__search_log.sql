-- =====================================================================
-- V9: 검색어 기록 (기획서 S-03: 최근 검색어, 인기·급상승 검색어)
-- =====================================================================
create table search_log (
    id            bigserial primary key,
    member_id     bigint       references member(id) on delete cascade,  -- 비회원 검색은 null
    keyword       varchar(100) not null,
    result_count  int          not null,
    hidden        boolean      not null default false,   -- 회원이 최근 검색어에서 지운 것
    created_at    timestamptz  not null
);
create index idx_search_log_created on search_log(created_at desc);
create index idx_search_log_member on search_log(member_id, created_at desc);
