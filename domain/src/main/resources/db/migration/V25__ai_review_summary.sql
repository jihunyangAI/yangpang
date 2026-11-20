-- =====================================================================
-- V25: M4 ⑧ AI 리뷰 요약 캐시 (Claude API, 키가 없으면 기능 꺼짐)
-- 리뷰가 5개 이상 늘었거나 7일이 지나면 다시 만든다
-- =====================================================================
create table review_summary (
    product_id    bigint       primary key references product(id) on delete cascade,
    summary       text         not null,
    pros          text,                     -- 줄바꿈으로 구분
    cons          text,
    review_count  int          not null,    -- 요약할 때의 리뷰 수
    model         varchar(50)  not null,
    created_at    timestamptz  not null
);
