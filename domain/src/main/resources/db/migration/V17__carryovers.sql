-- =====================================================================
-- V17: M3 이월 항목 — 홈 섹션 편집, 검색 동의어 편집, 찜 폴더 (캐시 소멸은 cash_ledger.expires_at 사용)
-- =====================================================================

-- 홈 화면 섹션 구성 (순서·노출·제목을 관리자가 편집)
create table home_section (
    kind        varchar(20)  primary key,   -- BANNER / SHORTCUTS / CATEGORIES / DEALS / EXHIBITIONS / RECENT / RANKING / FRESH / BEST
    title       varchar(50)  not null,
    visible     boolean      not null default true,
    sort_order  int          not null
);
insert into home_section(kind, title, visible, sort_order) values
    ('BANNER',      '메인 배너',          true, 10),
    ('SHORTCUTS',   '바로가기',           true, 20),
    ('DEALS',       '오늘의 특가',        true, 30),
    ('CATEGORIES',  '카테고리',           true, 40),
    ('EXHIBITIONS', '진행 중인 기획전',   true, 50),
    ('RECENT',      '최근 본 상품',       true, 60),
    ('RANKING',     '양팡 랭킹 TOP 10',   true, 70),
    ('FRESH',       '새벽배송 양팡프레시', true, 80),
    ('BEST',        '지금 많이 팔린 상품', true, 90);

-- 검색 동의어 (한 줄 = 같은 뜻 단어 묶음, 쉼표 구분. 양방향으로 적용)
create table search_synonym (
    id          bigserial primary key,
    words       varchar(300) not null,
    created_at  timestamptz  not null default now()
);
insert into search_synonym(words) values
    ('티셔츠, 티, 반팔'),
    ('무선이어폰, 이어폰, 블루투스이어폰'),
    ('에어프라이어, 에프'),
    ('보조배터리, 배터리, 충전기'),
    ('생수, 물'),
    ('즉석밥, 햇반'),
    ('두루마리휴지, 휴지, 화장지');

-- 찜 폴더
create table wishlist_folder (
    id          bigserial primary key,
    member_id   bigint      not null references member(id) on delete cascade,
    name        varchar(30) not null,
    created_at  timestamptz not null default now(),
    unique (member_id, name)
);
alter table wishlist add column folder_id bigint references wishlist_folder(id) on delete set null;
