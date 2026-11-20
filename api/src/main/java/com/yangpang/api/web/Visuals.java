package com.yangpang.api.web;

import com.yangpang.api.web.product.ProductPhotos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 배너·기획전·카테고리·브랜드·전문관 머리에 붙일 사진을 고른다 (글자만 있던 자리를 사진과 함께).
 * 별도 그림을 등록하지 않고 연결된 실제 상품의 사진을 쓴다 → 운영자가 새로 만든 배너·기획전에도 저절로 사진이 붙는다.
 * 같은 카테고리 상품은 사진을 나눠 쓰므로 겹치지 않는 사진만 고른다. 19세 이상 상품은 빼고, 결과는 10분 동안 기억한다.
 * 템플릿에서: ${@visuals.forLink(b.linkUrl, 3)}, ${@visuals.category(top.id)}, ${@visuals.brand(b.id, 3)}
 */
@Component("visuals")
@RequiredArgsConstructor
public class Visuals {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Pattern EVENT = Pattern.compile("^/events/([\\w-]+)");
    private static final Pattern CATEGORY = Pattern.compile("^/category/(\\d+)");
    private static final Pattern BRAND = Pattern.compile("^/brands/(\\d+)");

    private record Cached(List<String> urls, Instant at) {
    }

    private final JdbcTemplate jdbc;
    private final ProductPhotos photos;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** 링크가 가리키는 곳의 대표 사진 n장 (플러스 → 회원가 상품, 특가 → 오늘 특가 상품, 기획전 → 그 기획전 상품 …) */
    public List<String> forLink(String link, int n) {
        String path = link == null ? "" : link.split("\\?")[0];
        Matcher m;
        if ((m = EVENT.matcher(path)).find()) return exhibitionBySlug(m.group(1), n);
        if ((m = CATEGORY.matcher(path)).find()) return categoryPhotos(Long.parseLong(m.group(1)), n);
        if ((m = BRAND.matcher(path)).find()) return brand(Long.parseLong(m.group(1)), n);
        return switch (path) {
            case "/plus" -> pick("plus", "exists (select 1 from item i join vendor_item vi on vi.item_id = i.id where i.product_id = p.id and vi.plus_price is not null)", n);
            case "/deals" -> pick("deals", "exists (select 1 from deal d join vendor_item vi on vi.id = d.vendor_item_id join item i on i.id = vi.item_id where i.product_id = p.id and now() between d.starts_at and d.ends_at)", n);
            case "/fresh" -> pick("fresh", "p.rep_delivery_type = 'FRESH'", n);
            case "/global" -> pick("global", "p.rep_delivery_type = 'GLOBAL'", n);
            case "/refurb" -> pick("refurb", "exists (select 1 from item i join vendor_item vi on vi.item_id = i.id where i.product_id = p.id and vi.item_condition <> 'NEW')", n);
            default -> pick("popular", "true", n);
        };
    }

    /** 대분류 대표 사진 한 장 (그 분류에서 가장 많이 팔린 상품) */
    public String category(Long topId) {
        List<String> urls = categoryPhotos(topId, 1);
        return urls.isEmpty() ? null : urls.getFirst();
    }

    public List<String> categoryPhotos(Long categoryId, int n) {
        return pick("cat:" + categoryId, "p.category_id in (select id from category_tree where top_id = " + categoryId
                + " or mid_id = " + categoryId + " or id = " + categoryId + ")", n);
    }

    public List<String> brand(Long brandId, int n) {
        return pick("brand:" + brandId, "p.brand_id = " + brandId, n);
    }

    public List<String> exhibition(Long exhibitionId, int n) {
        return pick("ex:" + exhibitionId, "p.id in (select product_id from exhibition_product where exhibition_id = " + exhibitionId + ")", n);
    }

    private List<String> exhibitionBySlug(String slug, int n) {
        List<Long> ids = jdbc.queryForList("select id from exhibition where slug = ?", Long.class, slug);
        return ids.isEmpty() ? pick("popular", "true", n) : exhibition(ids.getFirst(), n);
    }

    /** where는 이 클래스 안에서 숫자·고정 문구로만 만든다 (사용자 입력을 넣지 않음) */
    private List<String> pick(String key, String where, int n) {
        String cacheKey = key + "#" + n;
        Cached c = cache.get(cacheKey);
        if (c != null && c.at().plus(TTL).isAfter(Instant.now())) return c.urls();
        List<Long> ids = jdbc.queryForList("select p.id from product p where p.status = 'ON_SALE' and not p.adult and ("
                + where + ") order by p.sales_count desc, p.id limit " + (n * 8), Long.class);
        Set<String> urls = new LinkedHashSet<>();
        List<String> fallback = new ArrayList<>();
        for (Long id : ids) {
            var photo = photos.url(id, 1);
            if (photo.isPresent()) urls.add(photo.get());
            else fallback.add("/images/products/" + id + "/1.svg");
            if (urls.size() >= n) break;
        }
        List<String> result = new ArrayList<>(urls);
        for (String f : fallback) {
            if (result.size() >= n) break;
            result.add(f);
        }
        cache.put(cacheKey, new Cached(List.copyOf(result), Instant.now()));
        return result;
    }
}
