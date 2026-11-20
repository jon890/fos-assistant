package com.bifos.assistant.admin.secret;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.admin.secret.application.ServerSecretAdminService;
import com.bifos.assistant.admin.secret.presentation.ServerSecretAdminController;
import com.bifos.assistant.browser.application.GatewaySecretService;
import com.bifos.assistant.connector.connection.application.ConnectorBrowserBindings;
import com.bifos.assistant.crypto.application.ServerSecretService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.user.application.UserDisplayNameService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * KEK 가 없는 설치에서 다시 만들기가 409 로 끝나고 바인딩을 다시 설치하지 않는지 본다. 공유 컨텍스트에서는 KEK 를 끌 수 없어 값 쓰기와
 * 바인딩 쪽만 대역으로 두고 조율 서비스는 실제로 돈다. 줄이 생기지 않는
 * 것은 {@code DatabaseServerSecretsWithoutKekTest} 가 본다.
 */
class ServerSecretAdminKekMissingTest {

    private static final CurrentUser ADMIN = new CurrentUser(2L, "dad@example.com", "아빠", 10L, UserRole.ADMIN);

    private final GatewaySecretService gateway = mock(GatewaySecretService.class);
    private final ConnectorBrowserBindings bindings = mock(ConnectorBrowserBindings.class);
    private final ServerSecretAdminService service = new ServerSecretAdminService(mock(ServerSecretService.class), gateway, bindings);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new ServerSecretAdminController(service, mock(UserDisplayNameService.class), new CurrentUserProvider()))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();


    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }


    @Test
    @DisplayName("KEK 가 없으면 다시 만들기는 409 SECRET_STORE_UNAVAILABLE 이고 바인딩을 다시 설치하지 않는다")
    void answersConflictWithoutKek() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ADMIN, null, List.of()));
        doThrow(new ApiException(ErrorCode.SECRET_STORE_UNAVAILABLE, "KEK is not configured")).when(gateway).regenerate(ADMIN.id());

        mvc.perform(post("/api/v1/admin/secrets/browser-gateway/regenerate"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SECRET_STORE_UNAVAILABLE"));
        verify(bindings, never()).reinstallAll();
    }
}
