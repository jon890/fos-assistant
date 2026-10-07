package com.bifos.assistant.browser.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bifos.assistant.browser.domain.RuntimeContainer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DockerProxyBrowserRuntimeTest {

    private static final String BASE = "https://browser-proxy.example.test";
    private static final String KEY = "0123456789abcdef".repeat(4);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private DockerProxyBrowserRuntime runtime;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        runtime = new DockerProxyBrowserRuntime(
                new BrowserProperties(
                        true,
                        BASE + "/",
                        "example/browser:test",
                        "example-browser",
                        9999,
                        "build/unused",
                        "/example/browser-profiles/",
                        "/example/profile",
                        1024,
                        1.5,
                        256,
                        128,
                        2,
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(30)),
                builder);
    }

    @Test
    @DisplayName("기능이 켜져 있는데 컨테이너 안 마운트 경로가 비어 있으면 기동을 멈춘다")
    void requiresProfileMountWhenEnabled() {
        assertThatThrownBy(() -> new BrowserProperties(
                        true,
                        BASE,
                        "example/browser:test",
                        "example-browser",
                        9999,
                        "build/unused",
                        "/example/browser-profiles",
                        " ",
                        1024,
                        1.5,
                        256,
                        128,
                        2,
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile-mount");
    }

    @Test
    @DisplayName("만들기 본문은 라벨 둘과 Binds 하나, 자원 상한과 권한 제한만 싣고 Entrypoint 와 Cmd, Env 는 싣지 않는다")
    void createsContainerWithPolicyBody() {
        server.expect(requestTo(BASE + "/containers/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.propertyNames()).containsExactlyInAnyOrder("Image", "Labels", "HostConfig");
                    assertThat(body.path("Image").asString()).isEqualTo("example/browser:test");
                    assertThat(body.path("Labels").propertyNames())
                            .containsExactlyInAnyOrder("fos-browser", "fos-browser-user");
                    assertThat(body.path("Labels").path("fos-browser").asString())
                            .isEqualTo("1");
                    assertThat(body.path("Labels").path("fos-browser-user").asString())
                            .isEqualTo(KEY);
                    JsonNode host = body.path("HostConfig");
                    assertThat(host.path("Binds")).hasSize(1);
                    assertThat(host.path("Binds").get(0).asString())
                            .isEqualTo("/example/browser-profiles/" + KEY + ":/example/profile:rw");
                    assertThat(host.path("NetworkMode").asString()).isEqualTo("example-browser");
                    assertThat(host.path("Memory").asLong()).isEqualTo(1024L * 1024 * 1024);
                    assertThat(host.path("MemorySwap").asLong())
                            .isEqualTo(host.path("Memory").asLong());
                    assertThat(host.path("NanoCpus").asLong()).isEqualTo(1_500_000_000L);
                    assertThat(host.path("PidsLimit").asInt()).isEqualTo(256);
                    assertThat(host.path("ShmSize").asLong()).isEqualTo(128L * 1024 * 1024);
                    assertThat(host.path("CapDrop").get(0).asString()).isEqualTo("ALL");
                    assertThat(host.path("SecurityOpt").get(0).asString()).isEqualTo("no-new-privileges");
                    assertThat(host.path("Init").asBoolean()).isTrue();
                    assertThat(host.path("RestartPolicy").path("Name").asString())
                            .isEqualTo("no");
                    assertThat(host.has("PortBindings")).isFalse();
                })
                .andRespond(withSuccess("{\"Id\":\"abc123\",\"Warnings\":[]}", MediaType.APPLICATION_JSON));

        assertThat(runtime.create(KEY)).isEqualTo("abc123");
        server.verify();
    }

    @Test
    @DisplayName("목록은 브라우저 라벨로 거르고 꺼진 것까지 받아 주인 키와 켜짐을 읽는다")
    void listsContainersFilteredByLabel() {
        server.expect(request -> {
                    URI uri = request.getURI();
                    assertThat(uri.getPath()).isEqualTo("/containers/json");
                    String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(query).contains("all=1").contains("filters={\"label\":[\"fos-browser=1\"]}");
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"Id":"a1","State":"running","Labels":{"fos-browser":"1","fos-browser-user":"%s"}},
                         {"Id":"a2","State":"exited","Labels":{"fos-browser":"1"}}]
                        """.formatted(KEY), MediaType.APPLICATION_JSON));

        List<RuntimeContainer> containers = runtime.list();

        assertThat(containers)
                .containsExactly(new RuntimeContainer("a1", KEY, true), new RuntimeContainer("a2", null, false));
    }

    @Test
    @DisplayName("켜기, 멈추기, 지우기는 정한 경로로 부르고 없는 컨테이너의 멈추기와 지우기는 성공으로 본다")
    void startsStopsAndRemovesContainer() {
        server.expect(requestTo(BASE + "/containers/abc/start"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(BASE + "/containers/abc/stop?t=10"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/containers/abc?force=1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        runtime.start("abc");
        runtime.stop("abc");
        runtime.remove("abc");
        server.verify();
    }

    @Test
    @DisplayName("proxy 가 거절하면 상태 코드만 담은 예외를 던지고 응답 본문은 싣지 않는다")
    void failsWithoutLeakingResponseBody() {
        server.expect(requestTo(BASE + "/containers/create"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("policy rejected secret-detail")
                        .contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> runtime.create(KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403")
                .hasMessageNotContaining("secret-detail");
    }

    @Test
    @DisplayName("CDP 주소는 브라우저 망의 IP 와 CDP 포트이고 컨테이너가 없으면 비어 있다")
    void readsCdpAddressFromBrowserNetwork() {
        server.expect(requestTo(BASE + "/containers/abc/json"))
                .andRespond(withSuccess("""
                        {"NetworkSettings":{"Networks":{"other":{"IPAddress":"198.51.100.9"},
                          "example-browser":{"IPAddress":"192.0.2.10"}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/containers/gone/json")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(runtime.cdpAddress("abc")).contains(URI.create("http://192.0.2.10:9999"));
        assertThat(runtime.cdpAddress("gone")).isEqualTo(Optional.empty());
    }
}
