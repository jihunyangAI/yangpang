package com.yangpang.domain.member;

import com.yangpang.domain.common.Region;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegionResolverTest {

    @Test
    void 서울_경기_인천은_수도권() {
        assertThat(RegionResolver.resolve("서울특별시 강남구 햇살로 1")).isEqualTo(Region.METRO);
        assertThat(RegionResolver.resolve("경기도 성남시 분당구")).isEqualTo(Region.METRO);
        assertThat(RegionResolver.resolve("인천광역시 연수구")).isEqualTo(Region.METRO);
    }

    @Test
    void 제주와_섬지역은_도서산간() {
        assertThat(RegionResolver.resolve("제주특별자치도 제주시")).isEqualTo(Region.REMOTE);
        assertThat(RegionResolver.resolve("경상북도 울릉군 울릉읍")).isEqualTo(Region.REMOTE);
        // 인천이지만 섬
        assertThat(RegionResolver.resolve("인천광역시 옹진군 백령면")).isEqualTo(Region.REMOTE);
    }

    @Test
    void 나머지는_지방() {
        assertThat(RegionResolver.resolve("부산광역시 해운대구")).isEqualTo(Region.LOCAL);
        assertThat(RegionResolver.resolve(null)).isEqualTo(Region.LOCAL);
    }
}
