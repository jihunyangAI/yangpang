package com.yangpang.domain.review;

import java.util.ArrayList;
import java.util.List;

/**
 * 카테고리별 맞춤 평가 항목 (기획서 5.11, 레퍼런스 17장).
 * 예: 패션은 "사이즈: 작아요 / 잘 맞아요 / 커요". 모든 상품에 "배송" 항목이 붙는다.
 */
public final class ReviewSurvey {

    private ReviewSurvey() {
    }

    public record Question(String name, List<String> choices) {
    }

    public static final Question DELIVERY = new Question("배송", List.of("빨라요", "보통이에요", "느려요"));

    /** @param topCategoryName 대분류 이름 */
    public static List<Question> questionsFor(String topCategoryName) {
        List<Question> qs = new ArrayList<>();
        String c = topCategoryName == null ? "" : topCategoryName;
        if (c.startsWith("패션")) {
            qs.add(new Question("사이즈", List.of("작아요", "잘 맞아요", "커요")));
            qs.add(new Question("색상", List.of("화면과 같아요", "조금 달라요", "많이 달라요")));
        } else if (c.startsWith("신선")) {
            qs.add(new Question("신선도", List.of("아주 신선해요", "보통이에요", "아쉬워요")));
            qs.add(new Question("맛", List.of("맛있어요", "보통이에요", "별로예요")));
        } else if (c.startsWith("식품") || c.startsWith("헬스")) {
            qs.add(new Question("맛", List.of("맛있어요", "보통이에요", "별로예요")));
        } else if (c.startsWith("뷰티")) {
            qs.add(new Question("피부 자극", List.of("순해요", "보통이에요", "자극 있어요")));
        } else if (c.startsWith("가전")) {
            qs.add(new Question("성능", List.of("만족해요", "보통이에요", "불만족해요")));
        } else {
            qs.add(new Question("품질", List.of("좋아요", "보통이에요", "아쉬워요")));
        }
        qs.add(DELIVERY);
        return qs;
    }
}
