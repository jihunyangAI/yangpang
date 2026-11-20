package com.yangpang.seed.data;

import java.util.List;

/** 가짜 리뷰·문의 문장 사전. {item}은 소분류 이름(상품 종류)으로 바뀐다 */
public final class ReviewDictionary {

    private ReviewDictionary() {
    }

    public static final List<String> GOOD_HEADLINES = List.of(
            "재구매 의사 있어요", "가격 대비 최고", "만족스러운 구매", "생각보다 훨씬 좋아요", "배송도 빠르고 좋아요",
            "선물용으로도 좋아요", "강력 추천합니다", "딱 원하던 {item}", "가성비 좋은 {item}");
    public static final List<String> OK_HEADLINES = List.of(
            "무난해요", "가격만큼 해요", "보통이에요", "나쁘지 않아요", "그럭저럭 쓸 만해요");
    public static final List<String> BAD_HEADLINES = List.of(
            "기대에 못 미쳐요", "아쉬워요", "재구매는 안 할 것 같아요", "사진과 달라요", "품질이 아쉬워요");

    public static final List<String> GOOD_SENTENCES = List.of(
            "{item} 찾다가 리뷰 보고 샀는데 정말 만족해요.",
            "마감이 깔끔하고 튼튼해 보여요.",
            "주문한 다음 날 바로 도착해서 좋았어요.",
            "가족들도 다 좋아하네요.",
            "이 가격에 이 정도면 충분히 만족합니다.",
            "포장이 꼼꼼하게 되어 있었어요.",
            "두 번째 구매인데 역시 좋네요.",
            "디자인이 깔끔해서 어디에 둬도 잘 어울려요.",
            "생각했던 것보다 크기가 적당하고 쓰기 편해요.");
    public static final List<String> OK_SENTENCES = List.of(
            "{item} 자체는 무난한데 특별한 장점은 모르겠어요.",
            "가격 생각하면 이 정도는 괜찮은 것 같아요.",
            "조금 더 써 봐야 알 것 같아요.",
            "배송은 빨랐는데 포장이 살짝 아쉬웠어요.",
            "설명이랑 거의 비슷해요.");
    public static final List<String> BAD_SENTENCES = List.of(
            "받아 보니 생각보다 마감이 아쉬워요.",
            "사진에서 본 색감과 조금 달라요.",
            "몇 번 쓰지 않았는데 벌써 아쉬운 부분이 보여요.",
            "가격이 저렴한 이유가 있는 것 같아요.",
            "기대가 컸는지 실망스러워요.");

    public static final List<String> SELLER_REPLIES = List.of(
            "소중한 의견 감사합니다. 말씀하신 부분은 꼼꼼히 확인해 개선하겠습니다.",
            "불편을 드려 죄송합니다. 고객센터로 연락 주시면 빠르게 도와드리겠습니다.",
            "구매해 주셔서 감사합니다. 더 좋은 상품으로 보답하겠습니다.");

    public static final List<String> QUESTIONS = List.of(
            "{item} 사이즈가 정사이즈인가요?", "언제 재입고되나요?", "오늘 주문하면 언제 도착하나요?",
            "선물 포장 가능한가요?", "구성품이 어떻게 되나요?", "세탁기 사용 가능한가요?",
            "반품하려면 어떻게 해야 하나요?", "다른 색상도 출시 예정인가요?", "제조일자가 언제인가요?",
            "{item} 실제 크기가 어느 정도인가요?");
    public static final List<String> ANSWERS = List.of(
            "안녕하세요, 고객님. 문의 주셔서 감사합니다. 상세페이지 하단의 정보를 참고해 주세요.",
            "안녕하세요. 현재 재고는 충분하며 오늘 주문 시 안내된 도착 예정일에 받아 보실 수 있습니다.",
            "고객님, 해당 내용은 확인 후 빠르게 안내해 드리겠습니다. 감사합니다.",
            "안녕하세요. 반품은 마이양팡 > 주문목록에서 신청하실 수 있습니다.",
            "문의 감사합니다. 말씀하신 부분은 가능합니다.");
}
