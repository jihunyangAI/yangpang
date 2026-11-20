package com.yangpang.domain.ad;

import com.yangpang.domain.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdServiceTest {

    private AdService.Bidder b(long kw, long product, int bid, double quality) {
        return new AdService.Bidder(kw, kw, product, bid, quality);
    }

    @Test
    void 순위는_입찰가x품질_비용은_다음_순위를_이길_만큼_더하기_10원() {
        // A: 500 x 1.0 = 500, B: 400 x 1.5 = 600, C: 300 x 1.0 = 300
        var w = AdService.auction(List.of(b(1, 11, 500, 1.0), b(2, 12, 400, 1.5), b(3, 13, 300, 1.0)), 3);
        assertThat(w).extracting(AdService.Winner::keywordId).containsExactly(2L, 1L, 3L);
        assertThat(w.get(0).cpc()).isEqualTo(344);  // ceil(500 / 1.5) + 10
        assertThat(w.get(1).cpc()).isEqualTo(310);  // ceil(300 / 1.0) + 10
        assertThat(w.get(2).cpc()).isEqualTo(100);  // 마지막은 최소 금액
    }

    @Test
    void 클릭당_비용은_입찰가를_넘지_않는다() {
        var w = AdService.auction(List.of(b(1, 11, 300, 1.0), b(2, 12, 300, 1.0)), 2);
        assertThat(w.get(0).cpc()).isEqualTo(300); // 300 + 10 → 입찰가 300으로 제한
    }

    @Test
    void 같은_상품은_한_번만_노출() {
        var w = AdService.auction(List.of(b(1, 11, 900, 1.0), b(2, 11, 800, 1.0), b(3, 12, 200, 1.0)), 5);
        assertThat(w).extracting(AdService.Winner::productId).containsExactly(11L, 12L);
    }

    @Test
    void 품질은_랭킹_0점_0_5부터_100점_1_5까지() {
        assertThat(AdService.quality(0)).isEqualTo(0.5);
        assertThat(AdService.quality(100)).isEqualTo(1.5);
        assertThat(AdService.quality(250)).isEqualTo(1.5);
    }

    @Test
    void 키워드_줄_읽기() {
        var m = AdService.parseKeywords("캠핑 텐트 400\n  텐트  \n의자 250, 접이식 의자 200");
        assertThat(m).containsEntry("캠핑 텐트", 400).containsEntry("텐트", 300).containsEntry("의자", 250).containsEntry("접이식 의자", 200);
    }

    @Test
    void 입찰가_범위_밖이면_거절() {
        assertThatThrownBy(() -> AdService.parseKeywords("텐트 50")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AdService.parseKeywords("텐트 20000")).isInstanceOf(BusinessException.class);
    }

    @Test
    void 키워드의_모든_단어가_상품_정보에_있어야_관련_키워드() {
        String text = "솔솔핏 초경량 즉석밥 솔솔핏 즉석밥 쌀/잡곡 식품";
        assertThat(AdService.relevant("즉석밥", text)).isTrue();
        assertThat(AdService.relevant("초경량 즉석밥", text)).isTrue();
        assertThat(AdService.relevant("다이어리", text)).isFalse();
        assertThat(AdService.relevant("즉석밥 다이어리", text)).isFalse();
    }
}
