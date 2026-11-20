package com.bifos.assistant.admin.model;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.admin.model.presentation.ModelAdminController;
import com.bifos.assistant.chat.modelchoice.application.GroupModelCatalogService;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.chat.modelchoice.application.ModelVisibilityService;
import com.bifos.assistant.chat.modelchoice.application.dto.CatalogRefresh;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 관리자가 모델 목록을 다시 읽는 경로의 권한과 응답 모양을 HTTP 경계에서 본다. */
class ModelCatalogRefreshRouteTest {

    private static final CurrentUser MEMBER = new CurrentUser(1L, "kid@example.com", "아이", 10L, UserRole.MEMBER);
    private static final CurrentUser ADMIN = new CurrentUser(2L, "dad@example.com", "아빠", 10L, UserRole.ADMIN);

    private final GroupModelCatalogService groupCatalog = mock(GroupModelCatalogService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ModelAdminController(
        new CurrentUserProvider(),
        mock(ModelVisibilityService.class),
        mock(ModelTierService.class),
        groupCatalog))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();


    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }


    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }


    @Test
    @DisplayName("MEMBER 역할의 다시 읽기는 403 이고 Hermes 에 묻는 서비스를 부르지 않는다")
    void forbidsMemberBeforeAskingHermes() throws Exception {
        signIn(MEMBER);

        mvc.perform(post("/api/v1/admin/model-catalog/refresh"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(groupCatalog);
    }


    @Test
    @DisplayName("관리자는 다시 읽은 결과와 마지막으로 다시 읽은 시각을 받는다")
    void adminGetsStatusAndRefreshedAt() throws Exception {
        signIn(ADMIN);
        when(groupCatalog.refreshAll())
            .thenReturn(new CatalogRefresh(CatalogRefresh.Status.REUSED, Instant.parse("2026-01-01T00:00:00Z")));

        mvc.perform(post("/api/v1/admin/model-catalog/refresh"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REUSED"))
            .andExpect(jsonPath("$.refreshedAt").value("2026-01-01T00:00:00Z"));
    }


    @Test
    @DisplayName("다시 읽지 못해도 200 으로 FAILED 를 답하고 성공한 적이 없으면 시각이 null 이다")
    void failureIsAnsweredAsStatusNotError() throws Exception {
        signIn(ADMIN);
        when(groupCatalog.refreshAll()).thenReturn(new CatalogRefresh(CatalogRefresh.Status.FAILED, null));

        mvc.perform(post("/api/v1/admin/model-catalog/refresh"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.refreshedAt").doesNotExist());
    }
}
