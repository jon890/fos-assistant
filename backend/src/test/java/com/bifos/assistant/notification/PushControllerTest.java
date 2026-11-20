package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.bifos.assistant.notification.application.PushDeviceService;
import com.bifos.assistant.notification.infra.VapidKeys;
import com.bifos.assistant.notification.presentation.PushController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 웹 푸시 경로의 응답 모양을 본다. 응답에 주소와 키 원문이 없어야 한다(ADR-20261120 / web-push). */
@BackendIntegrationTest
class PushControllerTest {

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/controller-device";
    private static final TestSubscriptionKeys KEYS = TestSubscriptionKeys.create();
    private static final String P256DH = KEYS.p256dh();
    private static final String AUTH = KEYS.auth();

    @Autowired
    PushDeviceService devices;

    @Autowired
    VapidKeys keys;

    @Autowired
    AppUserRepository users;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private Long userId;


    @BeforeEach
    void setUp() {
        String email = "push-" + UUID.randomUUID() + "@example.com";
        userId = users.save(AppUser.of(email, "푸시", 1L, UserRole.MEMBER)).id();
        when(currentUser.require()).thenReturn(new CurrentUser(userId, email, "푸시", 1L, UserRole.MEMBER));
        mvc = MockMvcBuilders.standaloneSetup(new PushController(currentUser, devices))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }


    @AfterEach
    void tearDown() {
        users.deleteById(userId);
    }


    @Test
    @DisplayName("설정은 켜짐과 공개 키를, 꺼지면 켜짐 거짓과 빈 공개 키를 준다")
    void config() throws Exception {
        JsonNode on = body(mvc.perform(get("/api/v1/push/config")).andReturn().getResponse(), 200);
        assertThat(on.path("enabled").asBoolean()).isTrue();
        assertThat(on.path("publicKey").asString()).isEqualTo(keys.publicKeyBase64Url()).isNotBlank();

        when(keys.enabled()).thenReturn(false);
        JsonNode off = body(mvc.perform(get("/api/v1/push/config")).andReturn().getResponse(), 200);
        assertThat(off.path("enabled").asBoolean()).isFalse();
        assertThat(off.path("publicKey").isNull()).isTrue();
    }


    @Test
    @DisplayName("등록과 목록은 번호, 이름, 등록 시각, 주소 해시만 주고 해제는 204 다")
    void registerListAndUnregister() throws Exception {
        String request = """
            {"endpoint":"%s","keys":{"p256dh":"%s","auth":"%s"},"name":"Mac Chrome","timeZone":"Asia/Seoul"}
            """.formatted(ENDPOINT, P256DH, AUTH);

        MockHttpServletResponse registered = mvc.perform(put("/api/v1/push/devices")
            .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse();
        JsonNode device = body(registered, 200);
        assertThat(device.path("name").asString()).isEqualTo("Mac Chrome");
        assertThat(device.path("endpointHash").asString()).isEqualTo(Sha256.hex(ENDPOINT));
        assertThat(device.has("id")).isTrue();
        assertThat(device.has("registeredAt")).isTrue();
        assertNoSecrets(registered);

        MockHttpServletResponse listed = mvc.perform(get("/api/v1/push/devices")).andReturn().getResponse();
        assertThat(body(listed, 200)).hasSize(1);
        assertNoSecrets(listed);

        MockHttpServletResponse removed = mvc.perform(delete("/api/v1/push/devices/current")
            .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + ENDPOINT + "\"}")).andReturn().getResponse();
        assertThat(removed.getStatus()).isEqualTo(204);
        assertThat(body(mvc.perform(get("/api/v1/push/devices")).andReturn().getResponse(), 200)).isEmpty();
    }


    @Test
    @DisplayName("키가 없는 서버의 등록은 409 PUSH_NOT_CONFIGURED 다")
    void registerWithoutKeys() throws Exception {
        when(keys.enabled()).thenReturn(false);
        String request = """
            {"endpoint":"%s","keys":{"p256dh":"%s","auth":"%s"},"name":"PC","timeZone":"Asia/Seoul"}
            """.formatted(ENDPOINT, P256DH, AUTH);

        MockHttpServletResponse response = mvc.perform(put("/api/v1/push/devices")
            .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("PUSH_NOT_CONFIGURED");
    }


    private static void assertNoSecrets(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
            .doesNotContain(ENDPOINT)
            .doesNotContain(P256DH)
            .doesNotContain(AUTH);
    }


    private JsonNode body(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
}
