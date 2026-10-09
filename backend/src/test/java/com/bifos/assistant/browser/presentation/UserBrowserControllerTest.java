package com.bifos.assistant.browser.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.browser.application.BrowserScreens;
import com.bifos.assistant.browser.application.BrowserUsage;
import com.bifos.assistant.browser.application.FakeCdp;
import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.RuntimeContainer;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.application.UserDisplayNameService;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 내 브라우저 경로와 관리자 경로가 HTTP 경계에서 돌려주는 상태 코드와 응답 모양을 본다. 계약은 {@code docs/features/user-browser.md} 의
 * 「API(사용자 브라우저)」 다.
 *
 * <p>요청자는 인증 필터가 채우는 것과 같은 보안 문맥으로 넣는다. 서비스는 실제 DB 와 대역 proxy 로 돈다.
 */
@BackendIntegrationTest
class UserBrowserControllerTest {

    private static final CurrentUser MEMBER = new CurrentUser(301L, "kid@example.com", "아이", 1L, UserRole.MEMBER);
    private static final CurrentUser ADMIN = new CurrentUser(302L, "dad@example.com", "아빠", 1L, UserRole.ADMIN);

    @Autowired
    UserBrowserRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private final List<String> containers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM user_browser");
        containers.clear();
        signIn(MEMBER);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM user_browser");
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("만들고 켜고 끄고 지우는 동안 상태와 유휴 시간을 돌려준다")
    void createsStartsStopsAndDeletes() throws Exception {
        MockMvc mvc = mvc(true);

        mvc.perform(get("/api/v1/browser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.idleTimeoutSeconds").value(600));
        mvc.perform(post("/api/v1/browser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.lastError").isEmpty());
        mvc.perform(post("/api/v1/browser"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.BROWSER_EXISTS.name()));
        mvc.perform(post("/api/v1/browser/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.lastActiveAt").isNotEmpty());
        mvc.perform(get("/api/v1/browser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        mvc.perform(post("/api/v1/browser/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
        mvc.perform(delete("/api/v1/browser")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/browser")).andExpect(jsonPath("$.exists").value(false));
        mvc.perform(post("/api/v1/browser/start"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.BROWSER_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 GET 은 200 으로 enabled 만 주고 쓰기는 503 이다")
    void answersDisabledFeature() throws Exception {
        MockMvc mvc = mvc(false);

        mvc.perform(get("/api/v1/browser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.exists").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist());
        mvc.perform(post("/api/v1/browser"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.BROWSER_DISABLED.name()));
        mvc.perform(post("/api/v1/browser/start")).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("일반 사용자는 관리자 경로에서 403 이다")
    void forbidsAdminPathsToMember() throws Exception {
        MockMvc mvc = mvc(true);

        mvc.perform(get("/api/v1/admin/browsers"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.name()));
        mvc.perform(post("/api/v1/admin/browsers/1/stop")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/browsers/1")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자는 모든 브라우저를 사용자 이름과 함께 보고 번호로 끄고 지운다")
    void letsAdminListStopAndDelete() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(post("/api/v1/browser"));
        mvc.perform(post("/api/v1/browser/start"));
        Long id = repository.findByUserId(MEMBER.id()).orElseThrow().id();
        signIn(ADMIN);

        mvc.perform(get("/api/v1/admin/browsers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].userId").value(MEMBER.id()))
                .andExpect(jsonPath("$[0].userName").value("아이"))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].containerId").doesNotExist())
                .andExpect(jsonPath("$[0].profileKey").doesNotExist());
        mvc.perform(post("/api/v1/admin/browsers/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
        mvc.perform(delete("/api/v1/admin/browsers/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/browsers"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private MockMvc mvc(boolean enabled) {
        UserBrowserService service = new UserBrowserService(
                repository,
                runtime(),
                profiles(),
                address -> true,
                LiveProperties.fixed(BrowserProperties.class, properties(enabled)),
                Clock.systemUTC(),
                new BrowserScreens(
                        new FakeCdp(),
                        new FakeCdp(),
                        new BrowserUsage(),
                        LiveProperties.fixed(BrowserProperties.class, properties(enabled))));
        CurrentUserProvider currentUser = new CurrentUserProvider();
        UserDisplayNameService names = mock(UserDisplayNameService.class);
        when(names.find(MEMBER.id())).thenReturn("아이");
        return MockMvcBuilders.standaloneSetup(
                        new UserBrowserController(service, currentUser),
                        new UserBrowserAdminController(service, names, currentUser))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    /** 만든 컨테이너 번호만 기억하는 대역 proxy 다. 켜기와 끄기의 순서는 서비스 검사가 본다. */
    private BrowserRuntime runtime() {
        return new BrowserRuntime() {
            @Override
            public String create(String profileKey) {
                containers.add("c" + containers.size());
                return containers.get(containers.size() - 1);
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
                containers.remove(containerId);
            }

            @Override
            public Optional<URI> cdpAddress(String containerId) {
                return Optional.of(URI.create("http://192.0.2.10:9999"));
            }

            @Override
            public OptionalInt exitCode(String containerId) {
                return OptionalInt.empty();
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
                Duration.ofMinutes(30),
                null,
                null);
    }
}
