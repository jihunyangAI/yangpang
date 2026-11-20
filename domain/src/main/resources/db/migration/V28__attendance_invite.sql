-- =====================================================================
-- V28: 출석체크 · 친구초대 (기획서 §18). 보상은 양팡캐시(모의), 금액은 운영 기준값으로
-- =====================================================================
create table attendance (
    member_id   bigint       not null references member(id) on delete cascade,
    day         date         not null,             -- 시뮬레이션 날짜 (KST)
    streak      int          not null,             -- 이날까지 연속 출석 일수
    reward      bigint       not null,             -- 이날 받은 캐시
    created_at  timestamptz  not null,
    primary key (member_id, day)
);

alter table member add column invite_code varchar(12) unique;
alter table member add column invited_by bigint references member(id) on delete set null;

-- 초대받은 회원 한 명당 한 줄. 가입 즉시 초대받은 쪽 보상, 첫 결제 때 초대한 쪽 보상(한 달 한도)
create table invite_reward (
    id              bigserial    primary key,
    inviter_id      bigint       not null references member(id) on delete cascade,
    invitee_id      bigint       not null unique references member(id) on delete cascade,
    status          varchar(20)  not null default 'PENDING',   -- PENDING(첫 결제 전) / REWARDED / LIMITED(한도 초과)
    inviter_reward  bigint       not null default 0,
    invitee_reward  bigint       not null default 0,
    created_at      timestamptz  not null,
    rewarded_at     timestamptz
);
create index idx_invite_reward_inviter on invite_reward(inviter_id, created_at desc);

insert into policy_setting(key, value, category, label, unit, min_value, max_value, description, sort_order) values
('ATTENDANCE_DAILY_CASH',   30, '혜택', '출석체크 하루 적립',        '원', 0, 1000,   '하루 한 번 출석하면 주는 양팡캐시', 60),
('ATTENDANCE_STREAK_BONUS', 300, '혜택', '출석체크 7일 연속 보너스', '원', 0, 10000,  '연속 출석 7일째마다 더 주는 양팡캐시', 70),
('INVITE_REWARD_CASH',     3000, '혜택', '친구초대 보상',            '원', 0, 50000,  '초대받은 친구는 가입 즉시, 초대한 회원은 친구의 첫 결제 때 받는 양팡캐시', 80),
('INVITE_MONTHLY_LIMIT',     10, '혜택', '친구초대 보상 월 한도',    '명', 0, 100,    '초대한 회원이 한 달에 보상받을 수 있는 친구 수 (넘으면 보상 없음)', 90);
