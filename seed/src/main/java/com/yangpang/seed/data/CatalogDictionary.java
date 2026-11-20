package com.yangpang.seed.data;

import java.util.List;

/**
 * MVP 카테고리 트리(대분류 16 / 중분류 48 / 소분류 96 = 160개)와 상품 생성 규칙.
 * 소분류 이름이 상품명의 핵심 명사가 된다.
 */
public final class CatalogDictionary {

    private CatalogDictionary() {
    }

    /** 옵션 축 유형 */
    public enum OptionType {
        FASHION,  // 색상 x 사이즈
        COLOR,    // 색상
        VOLUME,   // 용량
        WEIGHT,   // 중량
        COUNT,    // 수량(묶음)
        NONE      // 단일 상품
    }

    public record Mid(String name, List<String> smalls) {
    }

    public record Top(String name, String icon, OptionType optionType,
                      long minPrice, long maxPrice, boolean fresh, List<Mid> mids) {
    }

    public static final List<Top> TREE = List.of(
            new Top("패션의류/잡화", "shirt", OptionType.FASHION, 9_900, 89_000, false, List.of(
                    new Mid("여성의류", List.of("원피스", "블라우스")),
                    new Mid("남성의류", List.of("티셔츠", "청바지")),
                    new Mid("가방/잡화", List.of("백팩", "지갑")))),
            new Top("뷰티", "sparkles", OptionType.VOLUME, 8_000, 59_000, false, List.of(
                    new Mid("스킨케어", List.of("토너", "크림")),
                    new Mid("메이크업", List.of("립스틱", "쿠션팩트")),
                    new Mid("헤어/바디", List.of("샴푸", "바디워시")))),
            new Top("출산/유아동", "baby", OptionType.COUNT, 9_900, 79_000, false, List.of(
                    new Mid("기저귀/물티슈", List.of("기저귀", "물티슈")),
                    new Mid("유아식기", List.of("빨대컵", "이유식기")),
                    new Mid("유아의류", List.of("바디슈트", "내복")))),
            new Top("식품", "utensils", OptionType.WEIGHT, 3_900, 39_000, false, List.of(
                    new Mid("가공식품", List.of("라면", "즉석밥")),
                    new Mid("음료", List.of("생수", "커피믹스")),
                    new Mid("간식", List.of("견과류", "과자")))),
            new Top("주방용품", "cooking-pot", OptionType.COLOR, 5_900, 129_000, false, List.of(
                    new Mid("조리도구", List.of("프라이팬", "냄비")),
                    new Mid("식기", List.of("접시", "머그컵")),
                    new Mid("보관용기", List.of("밀폐용기", "지퍼백")))),
            new Top("생활용품", "spray-can", OptionType.COUNT, 2_900, 39_000, false, List.of(
                    new Mid("세제", List.of("세탁세제", "주방세제")),
                    new Mid("화장지", List.of("두루마리휴지", "키친타월")),
                    new Mid("욕실용품", List.of("칫솔", "수건")))),
            new Top("홈인테리어", "sofa", OptionType.COLOR, 9_900, 399_000, false, List.of(
                    new Mid("가구", List.of("책상", "의자")),
                    new Mid("침구", List.of("이불", "베개")),
                    new Mid("조명", List.of("스탠드", "LED등")))),
            new Top("가전디지털", "monitor", OptionType.COLOR, 19_900, 1_290_000, false, List.of(
                    new Mid("생활가전", List.of("청소기", "가습기")),
                    new Mid("주방가전", List.of("에어프라이어", "전기포트")),
                    new Mid("디지털기기", List.of("무선이어폰", "보조배터리")))),
            new Top("스포츠/레저", "dumbbell", OptionType.COLOR, 9_900, 299_000, false, List.of(
                    new Mid("헬스", List.of("덤벨", "요가매트")),
                    new Mid("캠핑", List.of("텐트", "캠핑의자")),
                    new Mid("자전거", List.of("자전거헬멧", "자전거라이트")))),
            new Top("자동차용품", "car", OptionType.NONE, 4_900, 199_000, false, List.of(
                    new Mid("세차용품", List.of("세차타월", "카샴푸")),
                    new Mid("차량용 전자기기", List.of("블랙박스", "차량용충전기")),
                    new Mid("차량 인테리어", List.of("차량용방향제", "시트커버")))),
            new Top("도서/음반", "book", OptionType.NONE, 9_000, 35_000, false, List.of(
                    new Mid("소설", List.of("장편소설", "단편소설")),
                    new Mid("자기계발", List.of("자기계발서", "경제경영서")),
                    new Mid("음반", List.of("가요앨범", "클래식앨범")))),
            new Top("완구/취미", "puzzle", OptionType.NONE, 6_900, 159_000, false, List.of(
                    new Mid("블록", List.of("블록세트", "대형블록")),
                    new Mid("보드게임", List.of("보드게임", "직소퍼즐")),
                    new Mid("프라모델", List.of("프라모델", "RC카")))),
            new Top("문구/오피스", "pencil", OptionType.COLOR, 1_900, 29_000, false, List.of(
                    new Mid("필기구", List.of("볼펜", "형광펜")),
                    new Mid("노트", List.of("스프링노트", "다이어리")),
                    new Mid("사무용품", List.of("스테이플러", "파일철")))),
            new Top("반려동물용품", "paw-print", OptionType.WEIGHT, 4_900, 89_000, false, List.of(
                    new Mid("강아지", List.of("강아지사료", "강아지간식")),
                    new Mid("고양이", List.of("고양이사료", "고양이모래")),
                    new Mid("반려용품", List.of("하네스", "펫방석")))),
            new Top("헬스/건강식품", "pill", OptionType.COUNT, 9_900, 89_000, false, List.of(
                    new Mid("비타민", List.of("종합비타민", "비타민C")),
                    new Mid("건강식품", List.of("유산균", "오메가3")),
                    new Mid("다이어트", List.of("단백질쉐이크", "다이어트차")))),
            new Top("신선식품", "apple", OptionType.WEIGHT, 3_900, 49_000, true, List.of(
                    new Mid("과일", List.of("사과", "바나나")),
                    new Mid("채소", List.of("양파", "상추")),
                    new Mid("정육/계란", List.of("돼지고기", "계란"))))
    );

    public static final List<String> ADJECTIVES = List.of(
            "프리미엄", "데일리", "베이직", "대용량", "가성비", "고급형", "초경량", "심플",
            "클래식", "무소음", "스탠다드", "에센셜", "내추럴", "모던", "컴팩트", "실속형");

    public static final List<String> COLORS = List.of("블랙", "화이트", "네이비", "베이지", "그레이", "핑크", "그린");
    public static final List<String> SIZES = List.of("S", "M", "L", "XL");
    public static final List<String> VOLUMES = List.of("50ml", "100ml", "200ml", "500ml");
    public static final List<String> WEIGHTS = List.of("500g", "1kg", "2kg", "5kg");
    public static final List<String> COUNTS = List.of("1개", "2개", "3개", "6개");

    public static final List<String> ORIGINS = List.of("국내산", "국내산", "국내산", "중국", "베트남", "미국");
}
