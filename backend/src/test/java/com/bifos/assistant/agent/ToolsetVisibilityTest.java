package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bifos.assistant.agent.application.ToolsetVisibilityService;
import com.bifos.assistant.agent.infra.ToolsetHiddenRepository;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@BackendIntegrationTest
class ToolsetVisibilityTest {
    private static final Long GROUP_ID = 9_000_000_061L;
    private final CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", GROUP_ID, UserRole.ADMIN);

    @Autowired
    ToolsetVisibilityService visibility;

    @Autowired
    ToolsetHiddenRepository hidden;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    HermesToolsetClient toolsets;

    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(status -> hidden.deleteByGroupId(GROUP_ID));
    }

    @Test
    @DisplayName("새 그룹은 모두 보이고 숨김 목록은 중복 없이 저장되며 다른 그룹에 번지지 않는다")
    void savesHiddenNamesForOnlyTheRequestedGroup() {
        assertThat(visibility.hiddenFor(GROUP_ID)).isEmpty();
        visibility.save(admin, List.of("spotify", "discord", "spotify"));
        assertThat(visibility.hiddenFor(GROUP_ID)).containsExactlyInAnyOrder("spotify", "discord");
        assertThat(visibility.hiddenFor(GROUP_ID + 1)).isEmpty();
        visibility.save(admin, List.of("web"));
        assertThat(visibility.hiddenFor(GROUP_ID)).containsExactly("web");
        visibility.save(admin, List.of());
        assertThat(visibility.hiddenFor(GROUP_ID)).isEmpty();
        verifyNoInteractions(toolsets);
    }

    @Test
    @DisplayName("잘못된 이름과 일반 사용자의 저장은 거절하고 기존 숨김을 보존한다")
    void rejectsInvalidAndUnauthorizedSavesWithoutChangingSettings() {
        visibility.save(admin, List.of("spotify"));
        assertThatThrownBy(() -> visibility.save(admin, List.of("memory")))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        CurrentUser member = new CurrentUser(2L, "member@example.com", "사용자", GROUP_ID, UserRole.MEMBER);
        assertThatThrownBy(() -> visibility.save(member, List.of()))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(visibility.hiddenFor(GROUP_ID)).containsExactly("spotify");
        verifyNoInteractions(toolsets);
    }
}
