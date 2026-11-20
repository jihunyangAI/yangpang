package com.yangpang.domain.review;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SurveyJsonTest {

    @Test
    void 쓰고_다시_읽으면_같다() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("사이즈", "잘 맞아요");
        m.put("배송", "빨라요");
        String json = SurveyJson.write(m);
        assertThat(json).isEqualTo("{\"사이즈\":\"잘 맞아요\",\"배송\":\"빨라요\"}");
        assertThat(SurveyJson.read(json)).isEqualTo(m);
    }

    @Test
    void 따옴표가_들어가도_안전() {
        Map<String, String> m = Map.of("a\"b", "c\\d");
        assertThat(SurveyJson.read(SurveyJson.write(m))).isEqualTo(m);
    }

    @Test
    void 비어있으면_null과_빈_맵() {
        assertThat(SurveyJson.write(Map.of())).isNull();
        assertThat(SurveyJson.read(null)).isEmpty();
    }
}
