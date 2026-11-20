-- =====================================================================
-- V8: 찜 · 최근 본 상품 (기획서 MY-05)
-- =====================================================================
create table wishlist (
    member_id   bigint      not null references member(id) on delete cascade,
    product_id  bigint      not null references product(id) on delete cascade,
    created_at  timestamptz not null default now(),
    primary key (member_id, product_id)
);

create table recent_view (
    member_id   bigint      not null references member(id) on delete cascade,
    product_id  bigint      not null references product(id) on delete cascade,
    viewed_at   timestamptz not null default now(),
    primary key (member_id, product_id)
);
create index idx_recent_view_member on recent_view(member_id, viewed_at desc);
