-- =====================================================================
-- V19: M4 ② 행동 로그 · 개인화 추천 (기획서 5.18 개인화 홈 피드, 재구매 추천, 함께 본 상품)
-- =====================================================================

-- 회원 행동 로그 (비회원은 기록하지 않음)
create table user_event (
    id          bigserial    primary key,
    member_id   bigint       not null references member(id) on delete cascade,
    event_type  varchar(20)  not null,   -- VIEW / CART / WISH / ORDER / SEARCH
    product_id  bigint       references product(id) on delete cascade,
    keyword     varchar(100),
    created_at  timestamptz  not null
);
create index idx_user_event_member on user_event(member_id, created_at desc);
create index idx_user_event_view on user_event(created_at, member_id) where event_type = 'VIEW';

-- 카테고리마다 대분류·중분류 (추천은 중분류 단위로 묶는다)
create view category_tree as
with recursive t(id, top_id, mid_id) as (
    select id, id, null::bigint from category where parent_id is null
    union all
    select c.id, t.top_id, case when c.depth = 2 then c.id else t.mid_id end
    from category c join t on c.parent_id = t.id
)
select id, top_id, coalesce(mid_id, id) as mid_id from t;

-- 함께 본 상품: 같은 회원이 같은 날 함께 본 횟수(회원 수). 시뮬레이터가 30분마다 다시 계산
create table product_co_view (
    product_id  bigint not null references product(id) on delete cascade,
    other_id    bigint not null references product(id) on delete cascade,
    score       int    not null,
    primary key (product_id, other_id)
);

-- 소모품 재구매 주기(일). 대분류에 두고 하위 전체에 적용. null이면 재구매 추천 대상 아님
alter table category add column repurchase_days int;
update category set repurchase_days = case name
    when '신선식품' then 7
    when '식품' then 21
    when '출산/유아동' then 21
    when '생활용품' then 30
    when '반려동물용품' then 30
    when '헬스/건강식품' then 30
    when '뷰티' then 45
    end
where parent_id is null;

-- 홈 섹션: 개인화 추천 (로그인 회원만, 행동 기록이 없으면 자동 숨김)
insert into home_section(kind, title, visible, sort_order) values ('FOR_YOU', '회원님을 위한 추천', true, 35)
on conflict (kind) do nothing;
