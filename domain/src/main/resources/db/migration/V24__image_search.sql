-- =====================================================================
-- V24: M4 ⑦ 이미지 검색 (색 분포 유사도, 외부 모델 없음)
-- 상품 대표 이미지(가짜 SVG와 같은 그림을 그려 계산)의 색 분포 52칸. 올린 사진은 저장하지 않는다
-- =====================================================================
create table product_image_feature (
    product_id   bigint       primary key references product(id) on delete cascade,
    hist         real[]       not null,
    swatch       varchar(10),             -- 색상 옵션으로 그린 색 이름 (없으면 null)
    computed_at  timestamptz  not null
);
