package com.yangpang.domain.deal;

import com.yangpang.domain.catalog.VendorItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UnitPriceTest {

    private static <T> T newInstance(Class<T> type) throws Exception {
        Constructor<T> c = type.getDeclaredConstructor();
        ReflectionUtils.makeAccessible(c);
        return c.newInstance();
    }

    private static VendorItem offer(long sale, Long plus) throws Exception {
        VendorItem vi = newInstance(VendorItem.class);
        ReflectionTestUtils.setField(vi, "id", 1L);
        ReflectionTestUtils.setField(vi, "salePrice", sale);
        ReflectionTestUtils.setField(vi, "plusPrice", plus);
        return vi;
    }

    private static Deal deal(long price, int quantity, int sold) throws Exception {
        Deal d = newInstance(Deal.class);
        ReflectionTestUtils.setField(d, "id", 9L);
        ReflectionTestUtils.setField(d, "dealPrice", price);
        ReflectionTestUtils.setField(d, "quantity", quantity);
        ReflectionTestUtils.setField(d, "sold", sold);
        return d;
    }

    @Test
    void 특가가_있으면_특가_적용() throws Exception {
        UnitPrice p = UnitPrice.of(offer(10_000, null), deal(7_000, 10, 3), false);
        assertThat(p.unit()).isEqualTo(7_000);
        assertThat(p.deal()).isNotNull();
        assertThat(p.plusSaving()).isZero();
    }

    @Test
    void 특가_수량이_다_팔리면_판매가() throws Exception {
        UnitPrice p = UnitPrice.of(offer(10_000, null), deal(7_000, 10, 10), false);
        assertThat(p.unit()).isEqualTo(10_000);
        assertThat(p.deal()).isNull();
    }

    @Test
    void 회원가가_더_싸면_회원가만_적용하고_특가_수량은_쓰지_않는다() throws Exception {
        UnitPrice p = UnitPrice.of(offer(10_000, 6_500L), deal(7_000, 10, 0), true);
        assertThat(p.unit()).isEqualTo(6_500);
        assertThat(p.deal()).isNull();
        assertThat(p.plusSaving()).isEqualTo(3_500);
    }

    @Test
    void 특가가_회원가보다_싸면_플러스_회원도_특가() throws Exception {
        UnitPrice p = UnitPrice.of(offer(10_000, 9_300L), deal(7_000, 10, 0), true);
        assertThat(p.unit()).isEqualTo(7_000);
        assertThat(p.deal()).isNotNull();
        assertThat(p.plusSaving()).isZero();
    }

    @Test
    void 일반_회원은_회원가를_받지_않는다() throws Exception {
        assertThat(UnitPrice.of(offer(10_000, 9_300L), null, false).unit()).isEqualTo(10_000);
        assertThat(UnitPrice.of(offer(10_000, 9_300L), null, true).unit()).isEqualTo(9_300);
    }

    @Test
    void 특가_기간은_매일_07시에_바뀐다() {
        Instant before7 = ZonedDateTime.parse("2026-11-20T06:59:00+09:00[Asia/Seoul]").toInstant();
        Instant after7 = ZonedDateTime.parse("2026-11-20T07:00:00+09:00[Asia/Seoul]").toInstant();
        assertThat(DealService.windowStart(before7)).isEqualTo(ZonedDateTime.parse("2026-11-19T07:00:00+09:00[Asia/Seoul]").toInstant());
        assertThat(DealService.windowStart(after7)).isEqualTo(after7);
    }

    @Test
    void 비즈_회원은_사업자가_비즈가_아니면_판매가() throws Exception {
        VendorItem vi = offer(10_000, 9_300L);
        ReflectionTestUtils.setField(vi, "bizPrice", 9_200L);
        UnitPrice biz = UnitPrice.of(vi, null, false, true);
        assertThat(biz.unit()).isEqualTo(9_200);
        assertThat(biz.plusSaving()).isZero(); // 플러스 절약액에 넣지 않음
        assertThat(UnitPrice.of(vi, null, false, false).unit()).isEqualTo(10_000);
        assertThat(UnitPrice.of(vi, null, true, true).unit()).isEqualTo(9_200); // 둘 다면 더 싼 쪽
    }

    @Test
    void 특가가_사업자가보다_싸면_특가() throws Exception {
        VendorItem vi = offer(10_000, null);
        ReflectionTestUtils.setField(vi, "bizPrice", 9_200L);
        UnitPrice p = UnitPrice.of(vi, deal(8_000, 10, 0), false, true);
        assertThat(p.unit()).isEqualTo(8_000);
        assertThat(p.deal()).isNotNull();
    }
}
