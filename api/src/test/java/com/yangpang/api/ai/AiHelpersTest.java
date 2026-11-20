package com.yangpang.api.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiHelpersTest {

    @Test
    void 답은_이스케이프하고_양팡_경로만_링크로() {
        String html = ChatService.render("**요가매트**는 /products/12 에서!\n<img src=x onerror=alert(1)> https://evil.example/products/3");
        assertThat(html).contains("<b>요가매트</b>");
        assertThat(html).contains("<a href=\"/products/12\"");
        assertThat(html).contains("&lt;img");
        assertThat(html).doesNotContain("<img");
        assertThat(html).doesNotContain("href=\"https://evil");   // 바깥 주소는 링크로 만들지 않음
        assertThat(html).contains("<br>");
    }

    @Test
    void 요약_JSON_코드블록도_읽고_3개까지() {
        var svc = new ReviewSummaryService(null, null);
        var s = svc.parse("```json\n{\"summary\": \"좋아요\", \"pros\": [\"a\",\"b\",\"c\",\"d\"], \"cons\": []}\n```", 7);
        assertThat(s.summary()).isEqualTo("좋아요");
        assertThat(s.pros()).containsExactly("a", "b", "c");
        assertThat(s.cons()).isEmpty();
        assertThat(s.reviewCount()).isEqualTo(7);
    }

    @Test
    void 요약_형식이_틀리면_예외() {
        var svc = new ReviewSummaryService(null, null);
        assertThatThrownBy(() -> svc.parse("죄송해요", 5)).isInstanceOf(AiClient.AiUnavailableException.class);
        assertThatThrownBy(() -> svc.parse("{\"summary\": \"\"}", 5)).isInstanceOf(AiClient.AiUnavailableException.class);
    }

    @Test
    void 리뷰가_5개_늘거나_7일_지나면_다시_만든다() {
        Instant now = Instant.parse("2026-11-21T00:00:00Z");
        var fresh = new ReviewSummaryService.Summary("s", List.of(), List.of(), 20, now.minus(Duration.ofDays(1)));
        assertThat(ReviewSummaryService.stale(fresh, 24, now)).isFalse();
        assertThat(ReviewSummaryService.stale(fresh, 25, now)).isTrue();
        var old = new ReviewSummaryService.Summary("s", List.of(), List.of(), 20, now.minus(Duration.ofDays(8)));
        assertThat(ReviewSummaryService.stale(old, 20, now)).isTrue();
    }

    @Test
    void 키가_없으면_꺼짐() {
        assertThat(new AiClient("", "claude-opus-5-5", "").enabled()).isFalse();
        assertThat(new AiClient("  ", "claude-opus-5-5", "").enabled()).isFalse();
    }
}
