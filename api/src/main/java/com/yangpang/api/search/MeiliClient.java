package com.yangpang.api.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Meilisearch HTTP 클라이언트 (필요한 API만).
 * 연결이 안 되면 잠시(30초) "사용 불가"로 표시해 두고, 그동안 호출하는 쪽은 PostgreSQL 검색으로 대신한다.
 */
@Slf4j
@Component
public class MeiliClient {

    public static final String INDEX = "products";

    private final RestClient http;
    private final boolean enabled;
    private volatile Instant downUntil = Instant.EPOCH;

    public MeiliClient(@Value("${yangpang.search.meili.url}") String url,
                       @Value("${yangpang.search.meili.key}") String key,
                       @Value("${yangpang.search.meili.enabled:true}") boolean enabled) {
        // Java 기본 HttpClient 사용 (HttpURLConnection 방식은 PATCH를 지원하지 않음)
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key).build();
        this.enabled = enabled;
    }

    public boolean available() {
        if (!enabled || Instant.now().isBefore(downUntil)) return false;
        try {
            Map<?, ?> r = http.get().uri("/health").retrieve().body(Map.class);
            return r != null && "available".equals(r.get("status"));
        } catch (RuntimeException e) {
            markDown(e);
            return false;
        }
    }

    public void updateSettings(Map<String, Object> settings) {
        http.patch().uri("/indexes/{i}/settings", INDEX).contentType(MediaType.APPLICATION_JSON).body(settings)
                .retrieve().toBodilessEntity();
    }

    public void createIndexIfMissing() {
        try {
            http.post().uri("/indexes").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("uid", INDEX, "primaryKey", "id")).retrieve().toBodilessEntity();
        } catch (RuntimeException ignored) {
            // 이미 있으면 Meilisearch가 작업 실패로 처리할 뿐 문제없음
        }
    }

    public void upsert(List<Map<String, Object>> docs) {
        if (docs.isEmpty()) return;
        http.post().uri("/indexes/{i}/documents", INDEX).contentType(MediaType.APPLICATION_JSON).body(docs)
                .retrieve().toBodilessEntity();
    }

    public void delete(Collection<Long> ids) {
        if (ids.isEmpty()) return;
        http.post().uri("/indexes/{i}/documents/delete-batch", INDEX).contentType(MediaType.APPLICATION_JSON).body(ids)
                .retrieve().toBodilessEntity();
    }

    /** 검색. 실패하면 null (호출하는 쪽이 대체 검색) */
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(Map<String, Object> body) {
        if (!enabled || Instant.now().isBefore(downUntil)) return null;
        try {
            return http.post().uri("/indexes/{i}/search", INDEX).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(Map.class);
        } catch (RuntimeException e) {
            markDown(e);
            return null;
        }
    }

    private void markDown(RuntimeException e) {
        downUntil = Instant.now().plusSeconds(30);
        log.warn("Meilisearch 사용 불가 (30초 동안 PostgreSQL 검색으로 대신): {}", e.getMessage());
    }
}
