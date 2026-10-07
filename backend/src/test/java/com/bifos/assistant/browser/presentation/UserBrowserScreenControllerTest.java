package com.bifos.assistant.browser.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.browser.application.BrowserScreens;
import com.bifos.assistant.browser.application.BrowserUsage;
import com.bifos.assistant.browser.application.FakeCdp;
import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.browser.domain.RuntimeContainer;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 로그인 화면 경로가 HTTP 경계에서 돌려주는 상태 코드를 본다. 계약은 {@code docs/backend/user-browser.md} 의 「로그인 화면」 이다.
 *
 * <p>요청자는 인증 필터가 채우는 것과 같은 보안 문맥으로 넣는다. 서비스는 실제 DB 와 대역 proxy, 대역 CDP 로 돈다.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserBrowserScreenControllerTest {

    private static final CurrentUser OWNER = new CurrentUser(311L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private static final CurrentUser OTHER = new CurrentUser(312L, "other@example.com", "남", 1L, UserRole.ADMIN);

    @Autowired
    UserBrowserRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private FakeCdp cdp;
    private BrowserScreens screens;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM user_browser");
        cdp = new FakeCdp();
        cdp.pages.add(new CdpTarget("T1", "첫 탭", "https://example.com/"));
        signIn(OWNER);
    }

    @AfterEach
    void tearDown() {
        if (screens != null) {
            screens.close();
        }
        jdbc.update("DELETE FROM user_browser");
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("화면을 열면 SSE 로 탭 목록을 먼저 보내고 입력을 받으면 204 다")
    void opensScreenAndAcceptsInput() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(post("/api/v1/browser")).andExpect(status().isOk());

        MvcResult opened = mvc.perform(get("/api/v1/browser/screen").param("url", "https://example.com/login"))
                .andExpect(request().asyncStarted())
                .andReturn();
        input(mvc, "{\"type\":\"text\",\"text\":\"안녕\"}").andExpect(status().isNoContent());

        assertThat(opened.getResponse().getContentAsString()).contains("event:tabs");
        assertThat(cdp.sent("Page.navigate").get(0).params()).containsEntry("url", "https://example.com/login");
        assertThat(cdp.sent("Input.insertText").get(0).params()).containsEntry("text", "안녕");
    }

    @Test
    @DisplayName("모양이 틀린 입력은 값을 싣지 않은 400 이다")
    void rejectsMalformedInput() throws Exception {
        MockMvc mvc = mvc(true);
        String longText = "가".repeat(501);
        String[] bodies = {
            "{\"type\":\"mouse\",\"action\":\"down\",\"x\":1.5,\"y\":0.5}",
            "{\"type\":\"mouse\",\"action\":\"down\",\"x\":0.5,\"y\":0.5,\"button\":\"right\"}",
            "{\"type\":\"wheel\",\"x\":0.5,\"y\":0.5,\"deltaY\":5000}",
            "{\"type\":\"text\",\"text\":\"" + longText + "\"}",
            "{\"type\":\"text\",\"text\":\"\"}",
            "{\"type\":\"navigate\",\"url\":\"javascript:alert('secret-value')\"}",
            "{\"type\":\"key\",\"key\":\"F5\"}",
            "{\"type\":\"tab\",\"id\":\"../browser\"}",
            "{\"type\":\"resize\",\"width\":100,\"height\":800}",
            "{\"type\":\"evaluate\",\"text\":\"secret-value\"}",
            "{\"type\":\"touch\"}",
            "{\"type\":\"text\",\"text\":secret-value}",
            "{\"type\":\"mouse\",\"action\":\"down\",\"x\":\"secret-value\",\"y\":0.5}",
            "",
        };
        for (String body : bodies) {
            MvcResult result = input(mvc, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("secret-value")
                    .doesNotContain(longText);
        }
    }

    @Test
    @DisplayName("시작 주소가 http 나 https 가 아니면 SSE 를 열지 않고 400 이다")
    void rejectsNonWebStartUrl() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(post("/api/v1/browser")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/browser/screen").param("url", "file:///etc/passwd"))
                .andExpect(status().isBadRequest());

        assertThat(cdp.attached).isEmpty();
    }

    @Test
    @DisplayName("열린 화면이 없으면 입력은 409 BROWSER_SCREEN_CLOSED 다")
    void rejectsInputWithoutScreen() throws Exception {
        input(mvc(true), "{\"type\":\"reload\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BROWSER_SCREEN_CLOSED"));
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 화면 열기와 입력 모두 503 이다")
    void rejectsWhenDisabled() throws Exception {
        MockMvc mvc = mvc(false);

        mvc.perform(get("/api/v1/browser/screen")).andExpect(status().isServiceUnavailable());
        input(mvc, "{\"type\":\"reload\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BROWSER_DISABLED"));
    }

    @Test
    @DisplayName("입력은 요청자의 화면에만 닿고 남의 화면에는 닿지 않는다")
    void doesNotReachOthersScreen() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(post("/api/v1/browser")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/browser/screen")).andExpect(request().asyncStarted());

        signIn(OTHER);
        input(mvc, "{\"type\":\"reload\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BROWSER_SCREEN_CLOSED"));

        assertThat(cdp.sent("Page.reload")).isEmpty();
    }

    private ResultActions input(MockMvc mvc, String body) throws Exception {
        return mvc.perform(post("/api/v1/browser/screen/input")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MockMvc mvc(boolean enabled) {
        screens = new BrowserScreens(cdp, cdp, new BrowserUsage(), properties(enabled));
        UserBrowserService service = new UserBrowserService(
                repository, runtime(), profiles(), address -> true, properties(enabled), Clock.systemUTC(), screens);
        return MockMvcBuilders.standaloneSetup(new UserBrowserController(service, new CurrentUserProvider()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    /** 컨테이너를 늘 켜진 것으로 보는 대역 proxy 다. */
    private static BrowserRuntime runtime() {
        return new BrowserRuntime() {
            @Override
            public String create(String profileKey) {
                return "c1";
            }

            @Override
            public void start(String containerId) {
                // 대역은 켜진 것으로 본다
            }

            @Override
            public void stop(String containerId) {
                // 대역은 멈춘 것으로 본다
            }

            @Override
            public void remove(String containerId) {
                // 대역은 지운 것으로 본다
            }

            @Override
            public Optional<URI> cdpAddress(String containerId) {
                return Optional.of(URI.create("http://192.0.2.10:9999"));
            }

            @Override
            public List<RuntimeContainer> list() {
                return List.of();
            }
        };
    }

    private static BrowserProfileStore profiles() {
        return new BrowserProfileStore() {
            @Override
            public void ensure(String profileKey) {
                // 검사는 디렉터리를 만들지 않는다
            }

            @Override
            public void delete(String profileKey) {
                // 검사는 디렉터리를 만들지 않는다
            }
        };
    }

    private static BrowserProperties properties(boolean enabled) {
        return new BrowserProperties(
                enabled,
                "https://browser-proxy.example.test",
                "example/browser:test",
                "example-browser",
                9999,
                "build/unused",
                "/example/browser-profiles",
                "/example/profile",
                512,
                1.0,
                256,
                128,
                2,
                Duration.ofMinutes(10),
                Duration.ofMillis(100),
                Duration.ofMinutes(30));
    }
}
