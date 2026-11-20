package com.yangpang.api.search;

import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.domain.catalog.ProductsChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 상품 → Meilisearch 색인.
 * 앱 시작 때 전체를 한 번 넣고, 이후에는 ProductsChangedEvent를 받아 바뀐 상품만 갱신한다 (DB 커밋 후, 별도 스레드).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIndexer {

    /** 동의어 (기획서 S-04 일부. 관리자 편집은 M3) */
    /** 동의어 묶음 (관리자 화면 /admin/synonyms 에서 편집, search_synonym 테이블) */
    List<List<String>> synonymGroups() {
        return jdbc.queryForList("select words from search_synonym order by id", String.class).stream()
                .map(w -> java.util.Arrays.stream(w.split(",")).map(String::trim).filter(t -> !t.isEmpty()).distinct().toList())
                .filter(g -> g.size() >= 2).toList();
    }

    private final MeiliClient meili;
    private final JdbcTemplate jdbc;
    private final CategoryMenu categoryMenu;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void reindexAllOnStartup() {
        reindexAll();
    }

    public int reindexAll() {
        if (!meili.available()) {
            log.warn("Meilisearch가 꺼져 있어 색인을 건너뜀 (PostgreSQL 검색 사용)");
            return 0;
        }
        meili.createIndexIfMissing();
        meili.updateSettings(settings());
        List<Map<String, Object>> docs = loadDocs(null);
        for (int i = 0; i < docs.size(); i += 1000) {
            meili.upsert(docs.subList(i, Math.min(i + 1000, docs.size())));
        }
        log.info("Meilisearch 전체 색인: 상품 {}개", docs.size());
        return docs.size();
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductsChanged(ProductsChangedEvent event) {
        try {
            if (!meili.available()) return;
            if (event.all()) {
                reindexAll();
                return;
            }
            List<Map<String, Object>> docs = loadDocs(event.productIds());
            List<Long> onSale = new ArrayList<>();
            List<Map<String, Object>> upserts = new ArrayList<>();
            for (Map<String, Object> d : docs) {
                onSale.add((Long) d.get("id"));
                upserts.add(d);
            }
            meili.upsert(upserts);
            // 판매중지 등으로 목록에서 빠진 상품은 색인에서 지운다
            List<Long> removed = event.productIds().stream().filter(id -> !onSale.contains(id)).toList();
            meili.delete(removed);
        } catch (RuntimeException e) {
            log.warn("상품 색인 갱신 실패 (다음 전체 색인 때 맞춰짐): {}", e.getMessage());
        }
    }

    /** 동의어를 양방향으로: 묶음 안의 어느 단어로 찾아도 나머지가 함께 나오도록 */
    static Map<String, List<String>> mutualSynonyms(List<List<String>> groups) {
        Map<String, java.util.Set<String>> m = new java.util.TreeMap<>();
        for (List<String> g : groups) {
            for (String a : g) {
                for (String b : g) {
                    if (!a.equals(b)) m.computeIfAbsent(a, k -> new java.util.TreeSet<>()).add(b);
                }
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        m.forEach((k, v) -> result.put(k, List.copyOf(v)));
        return result;
    }

    /** 검색 설정만 다시 보낸다 (동의어 편집 후) */
    public boolean pushSettings() {
        if (!meili.available()) return false;
        meili.updateSettings(settings());
        return true;
    }

    private Map<String, Object> settings() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("searchableAttributes", List.of("name", "brand", "categoryNames"));
        s.put("filterableAttributes", List.of("categoryIds", "deliveryType", "brandId", "price", "rating", "soldOut"));
        s.put("sortableAttributes", List.of("price", "salesCount", "createdAt", "reviewCount", "rating", "rankingScore"));
        // 관련도가 같으면 양팡 랭킹 점수가 높은 상품을 위로
        s.put("rankingRules", List.of("words", "typo", "proximity", "attribute", "sort", "exactness", "rankingScore:desc"));
        s.put("synonyms", mutualSynonyms(synonymGroups()));
        // 한국어는 3글자 단어가 많아 3글자부터 오타 1개 허용 (기본 5글자). 예: 청소가 → 청소기
        s.put("typoTolerance", Map.of("minWordSizeForTypos", Map.of("oneTypo", 3, "twoTypos", 7)));
        return s;
    }

    /** 판매중 상품 문서 (ids가 null이면 전체) */
    private List<Map<String, Object>> loadDocs(Set<Long> ids) {
        String sql = """
                select p.id, p.name, p.brand_id, b.name brand, p.category_id, p.rep_sale_price, p.rating_avg,
                       p.review_count, p.sales_count, p.sold_out, p.rep_delivery_type, p.ranking_score,
                       extract(epoch from p.created_at)::bigint created
                from product p left join brand b on b.id = p.brand_id
                where p.status = 'ON_SALE' and p.rep_delivery_type is not null
                """ + (ids == null ? "" : " and p.id = any(?)");
        Map<Long, List<Long>> pathIds = new HashMap<>();
        Map<Long, List<String>> pathNames = new HashMap<>();
        return jdbc.query(con -> {
            var ps = con.prepareStatement(sql);
            if (ids != null) ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
            return ps;
        }, (rs, i) -> {
            long categoryId = rs.getLong("category_id");
            List<CategoryMenu.Node> path = categoryMenu.path(categoryId);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", rs.getLong("id"));
            d.put("name", rs.getString("name"));
            d.put("brand", rs.getString("brand"));
            d.put("brandId", rs.getObject("brand_id"));
            d.put("categoryIds", pathIds.computeIfAbsent(categoryId, k -> path.stream().map(CategoryMenu.Node::id).toList()));
            d.put("categoryNames", pathNames.computeIfAbsent(categoryId, k -> path.stream().map(CategoryMenu.Node::name).toList()));
            d.put("price", rs.getLong("rep_sale_price"));
            d.put("rating", rs.getDouble("rating_avg"));
            d.put("reviewCount", rs.getInt("review_count"));
            d.put("salesCount", rs.getInt("sales_count"));
            d.put("rankingScore", rs.getDouble("ranking_score"));
            d.put("soldOut", rs.getBoolean("sold_out"));
            d.put("deliveryType", rs.getString("rep_delivery_type"));
            d.put("createdAt", rs.getLong("created"));
            return d;
        });
    }
}
