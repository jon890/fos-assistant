package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.RuntimeContainer;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * 브라우저 전용 Docker socket proxy 를 부른다. 경로는 버전 접두사 없는 Docker Engine API 다.
 *
 * <p>생성 본문의 규칙은 proxy 가 강제한다. 여기서는 그 규칙에 맞는 본문만 만든다. {@code Entrypoint}, {@code Cmd},
 * {@code Env}, 이름은 보내지 않는다. 실패는 상태 코드만 로그에 남기고 응답 본문은 남기지 않는다.
 */
@Slf4j
@Component
public class DockerProxyBrowserRuntime implements BrowserRuntime {

    /** 모든 브라우저 컨테이너에 붙는 라벨이다. 목록은 이 라벨로 거른다. */
    static final String BROWSER_LABEL = "fos-browser";
    /** 컨테이너 주인의 프로필 키를 적는 라벨이다. */
    static final String USER_LABEL = "fos-browser-user";
    /** 컨테이너 안에서 프로필 디렉터리를 붙이는 자리다. 이미지가 이 경로를 쓴다. */
    static final String PROFILE_MOUNT = "/example/profile";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 멈추기는 컨테이너가 끝나기를 10초까지 기다린다. 그보다 넉넉히 둔다. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private static final long MIB = 1024L * 1024L;
    private static final double NANOS_PER_CPU = 1_000_000_000d;
    private static final int NOT_FOUND = 404;
    private static final String LABEL_FILTER = "{\"label\":[\"" + BROWSER_LABEL + "=1\"]}";

    private final BrowserProperties properties;
    private final RestClient client;

    @Autowired
    public DockerProxyBrowserRuntime(BrowserProperties properties) {
        this(properties, RestClient.builder().requestFactory(requestFactory()));
    }

    DockerProxyBrowserRuntime(BrowserProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder.build();
    }

    @Override
    public String create(String profileKey) {
        JsonNode body = call(
                "create",
                () -> client.post()
                        .uri(path("/containers/create"))
                        .body(createBody(profileKey))
                        .retrieve()
                        .body(JsonNode.class));
        String id = body == null ? "" : body.path("Id").asString("");
        if (id.isBlank()) {
            throw new IllegalStateException("browser proxy create returned no container id");
        }
        return id;
    }

    @Override
    public void start(String containerId) {
        call(
                "start",
                () -> client.post()
                        .uri(path("/containers/{id}/start"), containerId)
                        .retrieve()
                        .toBodilessEntity());
    }

    @Override
    public void stop(String containerId) {
        ignoringMissing(
                "stop",
                () -> client.post()
                        .uri(path("/containers/{id}/stop?t=10"), containerId)
                        .retrieve()
                        .toBodilessEntity());
    }

    @Override
    public void remove(String containerId) {
        ignoringMissing(
                "remove",
                () -> client.delete()
                        .uri(path("/containers/{id}?force=1"), containerId)
                        .retrieve()
                        .toBodilessEntity());
    }

    @Override
    public Optional<URI> cdpAddress(String containerId) {
        JsonNode body;
        try {
            body = call(
                    "inspect",
                    () -> client.get()
                            .uri(path("/containers/{id}/json"), containerId)
                            .retrieve()
                            .body(JsonNode.class));
        } catch (MissingContainer ex) {
            return Optional.empty();
        }
        String ip = body == null
                ? ""
                : body.path("NetworkSettings")
                        .path("Networks")
                        .path(properties.network())
                        .path("IPAddress")
                        .asString("");
        if (ip.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(URI.create("http://" + ip + ":" + properties.cdpPort()));
    }

    @Override
    public List<RuntimeContainer> list() {
        URI uri = UriComponentsBuilder.fromUriString(path("/containers/json"))
                .queryParam("all", "1")
                .queryParam("filters", "{filters}")
                .encode()
                .buildAndExpand(LABEL_FILTER)
                .toUri();
        JsonNode body = call("list", () -> client.get().uri(uri).retrieve().body(JsonNode.class));
        List<RuntimeContainer> containers = new ArrayList<>();
        if (body == null || !body.isArray()) {
            return containers;
        }
        for (JsonNode node : body) {
            String key = node.path("Labels").path(USER_LABEL).asString("");
            containers.add(new RuntimeContainer(
                    node.path("Id").asString(""),
                    key.isBlank() ? null : key,
                    "running".equals(node.path("State").asString(""))));
        }
        return containers;
    }

    /** proxy 정책과 맞춘 생성 본문이다. 칸의 순서는 뜻이 없다. */
    Map<String, Object> createBody(String profileKey) {
        long memory = properties.memoryMb() * MIB;
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put(
                "Binds",
                List.of(trimSlash(properties.profileHostRoot()) + "/" + profileKey + ":" + PROFILE_MOUNT + ":rw"));
        hostConfig.put("NetworkMode", properties.network());
        hostConfig.put("Memory", memory);
        hostConfig.put("MemorySwap", memory);
        hostConfig.put("NanoCpus", Math.round(properties.cpu() * NANOS_PER_CPU));
        hostConfig.put("PidsLimit", properties.pidsLimit());
        hostConfig.put("ShmSize", properties.shmMb() * MIB);
        hostConfig.put("CapDrop", List.of("ALL"));
        hostConfig.put("SecurityOpt", List.of("no-new-privileges"));
        hostConfig.put("Init", true);
        hostConfig.put("RestartPolicy", Map.of("Name", "no"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Image", properties.image());
        body.put("Labels", Map.of(BROWSER_LABEL, "1", USER_LABEL, profileKey));
        body.put("HostConfig", hostConfig);
        return body;
    }

    private String path(String path) {
        String base = properties.proxyUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("assistant.browser.proxy-url is not configured");
        }
        return trimSlash(base) + path;
    }

    private void ignoringMissing(String action, Supplier<?> request) {
        try {
            call(action, request);
        } catch (MissingContainer ex) {
            log.debug("browser container already gone action={}", action);
        }
    }

    private static <T> T call(String action, Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == NOT_FOUND) {
                throw new MissingContainer(action);
            }
            throw failed(action, ex);
        } catch (RestClientResponseException ex) {
            throw failed(action, ex);
        }
    }

    private static IllegalStateException failed(String action, RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        log.warn("browser proxy request failed action={} status={}", action, status);
        return new IllegalStateException("browser proxy " + action + " failed with status " + status);
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /** proxy 가 그 컨테이너를 모른다고 답했다. 멈추기와 지우기에서는 성공으로 본다. */
    private static final class MissingContainer extends RuntimeException {

        MissingContainer(String action) {
            super("browser container not found action=" + action, null, false, false);
        }
    }
}
