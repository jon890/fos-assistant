package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.browser.domain.CdpTargets;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CDP 의 HTTP 창구({@code /json/list}, {@code /json/new}, {@code /json/activate})를 부른다.
 *
 * <p>주소는 컨테이너 IP 라서 Chrome 의 {@code Host} 검사를 그대로 통과한다. {@code Origin} 은 보내지 않는다. 실패 메시지에 응답 본문과
 * 주소를 싣지 않는다.
 */
@Component
public class HttpCdpTargets implements CdpTargets, AutoCloseable {

    /** CDP 대상 번호의 모양이다. 경로에 그대로 넣으므로 이것만 받는다. */
    static final Pattern TARGET_ID = Pattern.compile("^[A-Za-z0-9]+$");

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int OK = 200;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Override
    public List<CdpTarget> list(URI cdp) {
        JsonNode body = call(HttpRequest.newBuilder(endpoint(cdp, "/json/list")).GET(), "list");
        List<CdpTarget> targets = new ArrayList<>();
        for (JsonNode node : body) {
            if ("page".equals(node.path("type").asString(""))) {
                String id = node.path("id").asString("");
                if (TARGET_ID.matcher(id).matches()) {
                    targets.add(target(node));
                }
            }
        }
        return targets;
    }

    @Override
    public CdpTarget create(URI cdp, String url) {
        // Chrome 은 물음표 뒤를 풀어서 주소로 읽는다. 더하기는 풀지 않으므로 빈칸을 %20 으로 둔다
        String query = URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
        JsonNode body = call(
                HttpRequest.newBuilder(endpoint(cdp, "/json/new?" + query)).PUT(HttpRequest.BodyPublishers.noBody()),
                "create");
        if (!TARGET_ID.matcher(body.path("id").asString("")).matches()) {
            throw new IllegalStateException("cdp create returned no target id");
        }
        return target(body);
    }

    @Override
    public void activate(URI cdp, String id) {
        requireTargetId(id);
        send(HttpRequest.newBuilder(endpoint(cdp, "/json/activate/" + id)).GET(), "activate");
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    /** 대상 번호가 영문자와 숫자가 아니면 거절한다. */
    static void requireTargetId(String id) {
        if (id == null || !TARGET_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("cdp target id is not valid");
        }
    }

    private JsonNode call(HttpRequest.Builder request, String operation) {
        String body = send(request, operation);
        try {
            return JSON.readTree(body);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("cdp " + operation + " returned malformed json", ex);
        }
    }

    private String send(HttpRequest.Builder request, String operation) {
        HttpResponse<String> response;
        try {
            response = client.send(
                    request.timeout(TIMEOUT)
                            .header("Accept", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("cdp " + operation + " failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cdp " + operation + " interrupted", ex);
        }
        if (response.statusCode() != OK) {
            throw new IllegalStateException("cdp " + operation + " returned status " + response.statusCode());
        }
        return response.body();
    }

    private static CdpTarget target(JsonNode node) {
        return new CdpTarget(
                node.path("id").asString(""),
                node.path("title").asString(""),
                node.path("url").asString(""));
    }

    private static URI endpoint(URI cdp, String path) {
        if (cdp == null || !"http".equals(cdp.getScheme()) || cdp.getHost() == null) {
            throw new IllegalArgumentException("cdp address is not valid");
        }
        return URI.create("http://" + cdp.getRawAuthority() + path);
    }
}
