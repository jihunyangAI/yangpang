package com.yangpang.api.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Claude API 연결 (M4 ⑧). API 키가 없으면 꺼진다 — AI 리뷰 요약·챗봇 화면도 모두 숨긴다.
 * <ul>
 *   <li>모델 claude-opus-5-5, 노력(effort) low: 요약·쇼핑 도움 대화는 깊은 추론이 필요 없어 비용·지연을 줄인다</li>
 *   <li>서버 측 대체 모델(fallbacks: "default"): 안전 분류기가 거절하면 서버가 알맞은 모델로 다시 시도</li>
 *   <li>프롬프트 캐싱: 고정된 시스템 프롬프트에 캐시 지점 + 요청 전체 자동 캐싱(대화가 길어질수록 이전 부분 재사용)</li>
 * </ul>
 * 키: yangpang.ai.api-key (기본값은 환경 변수 ANTHROPIC_API_KEY). 테스트용으로 base-url을 바꿀 수 있다.
 */
@Slf4j
@Component
public class AiClient {

    public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    /** 호출 실패를 화면에 보여 줄 수 있게 감싼 예외 */
    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final AnthropicClient client;
    private final String model;

    public AiClient(@Value("${yangpang.ai.api-key:${ANTHROPIC_API_KEY:}}") String apiKey,
                    @Value("${yangpang.ai.model:claude-opus-5-5}") String model,
                    @Value("${yangpang.ai.base-url:}") String baseUrl) {
        this.model = model;
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
            log.info("[AI] API 키가 없어 AI 리뷰 요약·챗봇을 끕니다 (ANTHROPIC_API_KEY 또는 yangpang.ai.api-key)");
        } else {
            var b = AnthropicOkHttpClient.builder().apiKey(apiKey);
            if (baseUrl != null && !baseUrl.isBlank()) b.baseUrl(baseUrl);
            this.client = b.build();
            log.info("[AI] Claude API 사용 (모델 {}{})", model, baseUrl == null || baseUrl.isBlank() ? "" : ", base-url " + baseUrl);
        }
    }

    public boolean enabled() {
        return client != null;
    }

    public String model() {
        return model;
    }

    /**
     * 공통 설정을 채운 요청 빌더: 모델, 최대 토큰, 노력 low, 대체 모델, 캐시되는 시스템 프롬프트, 요청 전체 자동 캐싱.
     */
    public MessageCreateParams.Builder request(String systemPrompt, long maxTokens) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .addBeta(FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build()))
                .cacheControl(BetaCacheControlEphemeral.builder().build());
    }

    public BetaMessage create(MessageCreateParams params) {
        if (client == null) throw new AiUnavailableException("AI 기능이 꺼져 있어요", null);
        try {
            BetaMessage m = client.beta().messages().create(params);
            var u = m.usage();
            log.debug("[AI] 입력 {} (캐시 읽기 {}, 캐시 쓰기 {}) · 출력 {} · 종료 {}", u.inputTokens(),
                    u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L), u.outputTokens(),
                    m.stopReason().map(Object::toString).orElse("-"));
            return m;
        } catch (RateLimitException e) {
            log.warn("[AI] 요청 한도 초과: {}", e.getMessage());
            throw new AiUnavailableException("AI 요청이 몰려 있어요. 잠시 뒤 다시 시도해 주세요", e);
        } catch (AnthropicServiceException e) {
            log.warn("[AI] API 오류 {} {}", e.statusCode(), e.getMessage());
            throw new AiUnavailableException("지금은 AI가 답할 수 없어요", e);
        } catch (AnthropicIoException e) {
            log.warn("[AI] 연결 실패: {}", e.getMessage());
            throw new AiUnavailableException("AI 서버에 연결하지 못했어요", e);
        }
    }

    /** 응답의 텍스트 블록을 이어 붙인다 */
    public static String text(BetaMessage m) {
        StringBuilder sb = new StringBuilder();
        m.content().forEach(b -> b.text().ifPresent(t -> sb.append(t.text())));
        return sb.toString().trim();
    }

    @PreDestroy
    void close() {
        if (client != null) client.close();
    }
}
