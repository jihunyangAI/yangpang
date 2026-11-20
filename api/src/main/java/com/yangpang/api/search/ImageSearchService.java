package com.yangpang.api.search;

import com.yangpang.api.web.product.ProductImageSpecs;
import com.yangpang.api.web.product.ProductPhotos;
import com.yangpang.domain.imagesearch.ColorHistogram;
import com.yangpang.domain.imagesearch.ColorNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 이미지 검색 (M4 ⑦): 상품 대표 이미지(1번)의 색 분포를 미리 계산해 두고, 올린 사진의 색 분포와 비교한다.
 * 상품 1,000개 정도라 메모리에 올려 두고 전부 비교한다. 판매 중이고 품절이 아닌 상품만.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageSearchService {

    public record Hit(Long productId, double score) {
    }

    private record Entry(Long productId, float[] hist, double ranking) {
    }

    private final JdbcTemplate jdbc;
    private final ProductImageSpecs specs;
    private final ProductPhotos photos;
    private volatile List<Entry> cache;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        int n = ensureIndexed();
        if (n > 0) log.info("[이미지 검색] 상품 이미지 색 분포 {}개 계산", n);
    }

    /** 색 분포가 없는 상품만 계산해 저장. @return 계산한 수 */
    public synchronized int ensureIndexed() {
        List<Long> missing = jdbc.queryForList("""
                select p.id from product p where p.status = 'ON_SALE'
                and not exists (select 1 from product_image_feature f where f.product_id = p.id) order by p.id""", Long.class);
        for (Long id : missing) {
            // 카테고리 사진이 있으면 그 사진, 없으면 그림으로. 주요 색(swatch)은 사진이면 바탕(흰색) 뺀 가장 많은 색
            var photo = photos.image(id, 1);
            var spec = specs.spec(id, 1);
            float[] h = photo.map(ColorHistogram::ofPhoto).orElseGet(() -> ColorHistogram.of(specs.raster(spec, 128)));
            String swatch = photo.isPresent() ? mainColorName(h) : spec.swatchName();
            Float[] boxed = new Float[h.length];
            for (int i = 0; i < h.length; i++) boxed[i] = h[i];
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        insert into product_image_feature(product_id, hist, swatch, computed_at) values (?, ?, ?, ?)
                        on conflict (product_id) do update set hist = excluded.hist, swatch = excluded.swatch, computed_at = excluded.computed_at""");
                ps.setLong(1, id);
                Array arr = con.createArrayOf("real", boxed);
                ps.setArray(2, arr);
                ps.setString(3, swatch);
                ps.setTimestamp(4, Timestamp.from(Instant.now()));
                return ps;
            });
        }
        if (!missing.isEmpty() || cache == null) cache = load();
        return missing.size();
    }

    static String mainColorName(float[] h) {
        return ColorHistogram.topBins(h, 2, 0).stream().filter(b -> b != 3).findFirst()   // 3 = 흰색 칸(사진 바탕)
                .map(b -> ColorNames.nearest(ColorHistogram.binColor(b))).orElse("화이트");
    }

    /** 새로 계산하게 지운다 (운영자 "다시 계산") */
    public synchronized int reindexAll() {
        jdbc.update("delete from product_image_feature");
        cache = null;
        return ensureIndexed();
    }

    private List<Entry> load() {
        return jdbc.query("""
                select f.product_id, f.hist, coalesce(p.ranking_score, 0) from product_image_feature f join product p on p.id = f.product_id
                where p.status = 'ON_SALE' and not p.sold_out""", (rs, n) -> {
            Object[] arr = (Object[]) rs.getArray(2).getArray();
            float[] h = new float[ColorHistogram.BINS];
            for (int i = 0; i < Math.min(arr.length, h.length); i++) h[i] = ((Number) arr[i]).floatValue();
            return new Entry(rs.getLong(1), h, rs.getDouble(3));
        });
    }

    /** 비슷한 순 (같은 점수면 양팡 랭킹 점수 높은 순) */
    public List<Hit> search(float[] query, int limit, double minScore) {
        ensureIndexed();
        List<Entry> all = cache == null ? List.of() : cache;
        List<Object[]> scored = new ArrayList<>(all.size());
        for (Entry e : all) scored.add(new Object[]{e, ColorHistogram.similarity(query, e.hist())});
        return scored.stream()
                .filter(x -> (Double) x[1] >= minScore)
                .sorted(Comparator.comparingDouble((Object[] x) -> -(Double) x[1])
                        .thenComparingDouble(x -> -((Entry) x[0]).ranking()))
                .limit(limit)
                .map(x -> new Hit(((Entry) x[0]).productId(), (Double) x[1]))
                .toList();
    }
}
