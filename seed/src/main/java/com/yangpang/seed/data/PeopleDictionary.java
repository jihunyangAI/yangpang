package com.yangpang.seed.data;

import java.util.List;

/**
 * 가짜 사람·주소·브랜드 생성용 사전.
 * - 이름: 성 + 음절 두 개를 무작위로 조합 (실존 인물을 지정하지 않음)
 * - 주소: 실제 시·구 이름 + 가짜 도로명
 * - 브랜드: 음절 조합으로 만든 가상 이름
 */
public final class PeopleDictionary {

    private PeopleDictionary() {
    }

    public static final List<String> SURNAMES = List.of(
            "김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서", "신", "권", "황", "안", "송", "류", "홍");

    public static final List<String> GIVEN_SYLLABLES = List.of(
            "민", "서", "지", "현", "우", "준", "하", "윤", "도", "예", "수", "연", "은", "채", "주", "건",
            "시", "유", "진", "아", "태", "다", "소", "결", "율", "온", "새", "봄", "린", "호");

    /** 시도, 시군구, 권역(METRO/LOCAL/REMOTE), 우편번호 앞자리 */
    public record District(String sido, String sigungu, String region, String zipPrefix) {
    }

    public static final List<District> DISTRICTS = List.of(
            new District("서울특별시", "강남구", "METRO", "06"),
            new District("서울특별시", "마포구", "METRO", "04"),
            new District("서울특별시", "송파구", "METRO", "05"),
            new District("서울특별시", "노원구", "METRO", "01"),
            new District("경기도", "성남시 분당구", "METRO", "13"),
            new District("경기도", "수원시 영통구", "METRO", "16"),
            new District("경기도", "고양시 일산동구", "METRO", "10"),
            new District("인천광역시", "연수구", "METRO", "21"),
            new District("부산광역시", "해운대구", "LOCAL", "48"),
            new District("대구광역시", "수성구", "LOCAL", "42"),
            new District("대전광역시", "유성구", "LOCAL", "34"),
            new District("광주광역시", "서구", "LOCAL", "61"),
            new District("강원특별자치도", "춘천시", "LOCAL", "24"),
            new District("전북특별자치도", "전주시 완산구", "LOCAL", "55"),
            new District("경상남도", "창원시 성산구", "LOCAL", "51"),
            new District("제주특별자치도", "제주시", "REMOTE", "63"),
            new District("경상북도", "울릉군", "REMOTE", "40"));

    public static final List<String> ROAD_WORDS = List.of(
            "햇살", "푸른숲", "은행나무", "별빛", "한마음", "새봄", "솔바람", "꽃마을", "누리", "가온", "다솜", "미리내");

    public static final List<String> ROAD_SUFFIXES = List.of("로", "길", "대로");

    public static final List<String> BUILDING_SUFFIXES = List.of("아파트", "빌라", "오피스텔", "타운", "하우스");

    public static final List<String> REQUEST_NOTES = List.of(
            "문 앞에 놓아주세요", "경비실에 맡겨주세요", "택배함에 넣어주세요", "직접 받을게요", "배송 전 연락 부탁드립니다");

    public static final List<String> BRAND_PREFIXES = List.of(
            "모아", "데일리", "하루", "온결", "누리", "가온", "포근", "바른", "맑은", "단단", "솔솔", "새봄",
            "오늘", "한결", "다온", "라온", "늘봄", "도담", "소담", "미소");

    public static final List<String> BRAND_SUFFIXES = List.of(
            "홈", "핏", "랩", "플러스", "리빙", "마켓", "공방", "상회", "웍스", "케어", "키친", "스토어");
}
