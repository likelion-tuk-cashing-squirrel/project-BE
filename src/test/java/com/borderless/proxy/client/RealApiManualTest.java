package com.borderless.proxy.client;

import com.borderless.proxy.client.config.DeepLProperties;
import com.borderless.proxy.client.config.OpenAiProperties;
import com.borderless.proxy.client.dto.LlmRequest;
import com.borderless.proxy.client.dto.LlmResponse;
import com.borderless.proxy.client.dto.TranslationRequest;
import com.borderless.proxy.client.dto.TranslationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 실제 OpenAI / DeepL을 호출하는 수동 확인용 테스트
 * <p>
 * 과금이 발생하고 DeepL 할당량을 소모하므로 평소에는 스킵
 * 실행하려면 환경변수 RUN_REAL_API_TEST=true 를 설정
 * CI에는 이 변수가 없으므로 자동으로 스킵
 * <p>
 * 필요한 환경변수:
 *   RUN_REAL_API_TEST=true
 *   OPENAI_API_KEY=sk-proj-...
 *   DEEPL_API_KEY=xxxx:fx
 *   DEEPL_BASE_URL=https://api-free.deepl.com  (선택, 없으면 키 종류로 자동 판별)
 */
@EnabledIfEnvironmentVariable(named = "RUN_REAL_API_TEST", matches = "true")
class RealApiManualTest {

    @Test
    @DisplayName("실제 OpenAI 호출")
    void callOpenAi() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setApiKey(System.getenv("OPENAI_API_KEY"));
        properties.setBaseUrl(System.getenv("OPENAI_BASE_URL"));
        properties.setModel(System.getenv("OPENAI_DEFAULT_MODEL"));

        WebClient webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        LlmResponse response = new OpenAiLlmClient(webClient, properties)
                .complete(LlmRequest.of("한 문장으로 자기소개 해줘"))
                .block();

        System.out.println("=== OpenAI ===");
        System.out.println("본문   : " + response.getContent());
        System.out.println("모델   : " + response.getModel());
        System.out.println("입력   : " + response.getUsage().getPromptTokens() + " tokens");
        System.out.println("출력   : " + response.getUsage().getCompletionTokens() + " tokens");
        System.out.println("지연   : " + response.getLatencyMs() + " ms");
        System.out.println("종료   : " + response.getFinishReason());
    }

    @Test
    @DisplayName("실제 DeepL 호출")
    void callDeepL() {
        DeepLProperties properties = new DeepLProperties();
        properties.setApiKey(System.getenv("DEEPL_API_KEY"));

        String baseUrl = System.getenv("DEEPL_BASE_URL");
        properties.setBaseUrl(baseUrl != null ? baseUrl : properties.recommendedBaseUrl());

        WebClient webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        TranslationResponse response = new DeepLTranslationClient(webClient, properties)
                .translate(TranslationRequest.of("오늘 회의는 3시에 시작합니다", "EN"))
                .block();

        System.out.println("=== DeepL ===");
        System.out.println("baseUrl : " + properties.getBaseUrl());
        System.out.println("번역    : " + response.getFirstText());
        System.out.println("감지    : " + response.getTranslations().get(0).getDetectedSourceLang());
        System.out.println("과금자수 : " + response.getTotalBilledCharacters());
        System.out.println("지연    : " + response.getLatencyMs() + " ms");
    }

    @Test
    @DisplayName("실제 DeepL 호출 - 마스킹 토큰 보존 확인")
    void callDeepLWithMaskedToken() {
        DeepLProperties properties = new DeepLProperties();
        properties.setApiKey(System.getenv("DEEPL_API_KEY"));

        String baseUrl = System.getenv("DEEPL_BASE_URL");
        properties.setBaseUrl(baseUrl != null ? baseUrl : properties.recommendedBaseUrl());

        WebClient webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        TranslationResponse response = new DeepLTranslationClient(webClient, properties)
                .translate(TranslationRequest.builder()
                        .texts(java.util.List.of("<x>{TERM_01}</x> 프로젝트의 일정은 다음 주에 확정됩니다"))
                        .targetLang("EN")
                        .preserveMaskedTokens(true)
                        .build())
                .block();

        System.out.println("=== DeepL (마스킹 보존) ===");
        System.out.println("번역 : " + response.getFirstText());
        System.out.println("→ 결과에 <x>{TERM_01}</x> 이 그대로 남아 있어야 TermRestorer가 복원할 수 있습니다.");
    }
}
