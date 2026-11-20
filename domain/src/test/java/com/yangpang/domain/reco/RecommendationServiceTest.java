package com.yangpang.domain.reco;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationServiceTest {

    @Test
    void 자리는_점수_비율대로_나누고_합은_정확히_limit() {
        int[] q = RecommendationService.quotas(new double[]{60, 30, 10}, 10);
        assertThat(Arrays.stream(q).sum()).isEqualTo(10);
        assertThat(q[0]).isGreaterThan(q[1]);
        assertThat(q[1]).isGreaterThanOrEqualTo(q[2]);
        assertThat(q[2]).isGreaterThanOrEqualTo(2); // 관심사마다 최소 2자리
    }

    @Test
    void 관심사가_하나면_전부_그쪽() {
        assertThat(RecommendationService.quotas(new double[]{5}, 10)).containsExactly(10);
    }

    @Test
    void 자리가_모자라면_최소_자리를_줄인다() {
        int[] q = RecommendationService.quotas(new double[]{9, 5, 3, 1}, 4);
        assertThat(Arrays.stream(q).sum()).isEqualTo(4);
    }

    @Test
    void 번갈아_섞고_중복과_초과는_뺀다() {
        List<Long> out = RecommendationService.interleave(List.of(List.of(1L, 2L, 3L), List.of(10L, 2L), List.of(20L)), 5);
        assertThat(out).containsExactly(1L, 10L, 20L, 2L, 3L);
    }

    @Test
    void 재구매_예정일과_지난_날수() {
        var r = new RecommendationService.Reorder(1L, Instant.parse("2026-11-01T03:00:00Z"), 14, false, 9L, LocalDate.of(2026, 11, 15));
        assertThat(r.overdueDays(LocalDate.of(2026, 11, 20))).isEqualTo(5);
        assertThat(r.overdueDays(LocalDate.of(2026, 11, 13))).isEqualTo(-2);
    }
}
