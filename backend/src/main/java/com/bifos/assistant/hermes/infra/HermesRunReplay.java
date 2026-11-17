package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.config.HermesProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 끝난 실행의 사건 보관분을 한 번 읽어 끝 사건만 꺼낸다.
 *
 * <p>Hermes 는 끝난 run 에 처음 연결하면 보관한 사건을 보내고 스트림을 닫는다. 보관은 끝나고 5분 동안이다. 사건 스트림을 열지 않은
 * 호출자(위임, 흐름, 방 요약, 추천 질문, 재기동 정리)도 v0.23.2 에서 실행 조회가 주지 않는 {@code error} 와 {@code pending_steer} 를
 * 얻으려고 쓴다(docs/features/hermes-upgrade/design.md 의 「결과 칸 채우기」). 사건을 화면이나 실행 기록에 넘기지 않는다.
 */
@Component
@Slf4j
public class HermesRunReplay {

    private final RestClient restClient;
    private final HermesProfileKeyStore keyStore;
    private final ObjectMapper objectMapper;


    public HermesRunReplay(
        HermesProfileKeyStore keyStore,
        HermesProperties properties,
        ObjectMapper objectMapper,
        @Value("${assistant.hermes.terminal-event-read-timeout:5s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.keyStore = keyStore;
        this.objectMapper = objectMapper;
    }


    /** 끝 사건을 찾으면 그것이고, 닿지 못했거나 보관분이 없거나 끝 사건이 없으면 빈 값이다. 예외를 올리지 않는다. */
    public Optional<HermesRunTerminals.Terminal> readTerminal(String apiBaseUrl, String profileName, String runId) {
        try (InputStream body = restClient
            .get()
            .uri(apiBaseUrl + "/v1/runs/{runId}/events", runId)
            .header("Authorization", "Bearer " + keyStore.resolve(profileName))
            .accept(MediaType.TEXT_EVENT_STREAM)
            .retrieve()
            .body(InputStream.class)) {
            return body == null ? Optional.empty() : firstTerminal(body);
        } catch (RestClientException | IOException ex) {
            log.info("끝난 실행의 사건 보관분을 읽지 못했다 profile={} runId={}", profileName, runId);
            return Optional.empty();
        }
    }


    private Optional<HermesRunTerminals.Terminal> firstTerminal(InputStream body) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    data.append(data.isEmpty() ? "" : "\n").append(line.substring(5).stripLeading());
                } else if (line.isEmpty() && !data.isEmpty()) {
                    Optional<HermesRunTerminals.Terminal> found = terminalOf(data.toString());
                    data.setLength(0);
                    if (found.isPresent()) {
                        return found;
                    }
                }
            }
            return data.isEmpty() ? Optional.empty() : terminalOf(data.toString());
        }
    }


    private Optional<HermesRunTerminals.Terminal> terminalOf(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode type = root.has("event") ? root.get("event") : root.path("type");
            return type.isTextual()
                ? HermesRunTerminals.Terminal.ofTerminalEvent(type.asText().toLowerCase(Locale.ROOT), root)
                : Optional.empty();
        } catch (JacksonException ex) {
            return Optional.empty();
        }
    }
}
