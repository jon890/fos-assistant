package com.bifos.assistant.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.admin.memory.presentation.AgentMemorySettingController;
import com.bifos.assistant.admin.memory.presentation.MemoryAdminDtos.AgentMemoryGrantBody;
import com.bifos.assistant.admin.memory.presentation.MemoryAdminDtos.ReplaceAgentMemoryRequest;
import com.bifos.assistant.agent.application.AgentMemoryCollectionService;
import com.bifos.assistant.memory.application.dto.AgentMemoryGrantInput;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 에이전트 collection 설정의 권한과 입력 모양 검증을 Spring 과 DB 없이 확인한다. 거절한 요청이 저장소를 부르지 않았는지도 본다. */
class AgentMemorySettingGuardTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "admin@example.com", "admin", 10L, UserRole.ADMIN);

    private final AgentMemoryCollectionService agentCollections = mock(AgentMemoryCollectionService.class);
    private final AgentMemorySettingService service = new AgentMemorySettingService(
        agentCollections,
        mock(MemoryCollectionService.class),
        mock(MemoryRepository.class),
        mock(AppUserRepository.class));


    @Test
    @DisplayName("ADMIN 이 아니면 읽지도 바꾸지도 못하고 서비스를 부르지 않는다")
    void forbidsNonAdmins() {
        AgentMemorySettingService settings = mock(AgentMemorySettingService.class);
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        when(currentUser.requireAdmin())
            .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin"));
        AgentMemorySettingController controller = new AgentMemorySettingController(settings, currentUser);

        assertCode(() -> controller.get("agent-a"), ErrorCode.FORBIDDEN);
        assertCode(
            () -> controller.replace("agent-a", new ReplaceAgentMemoryRequest(List.of(new AgentMemoryGrantBody("career")))),
            ErrorCode.FORBIDDEN);

        verifyNoInteractions(settings);
    }


    @Test
    @DisplayName("같은 collection 이 겹치거나 64개를 넘는 목록은 에이전트를 읽기 전에 거절한다")
    void rejectsDuplicateAndTooManyCollectionsBeforeReadingTheAgent() {
        assertCode(() -> service.replace(ADMIN, "agent-a", inputs("career", "career")), ErrorCode.VALIDATION_FAILED);
        assertCode(
            () -> service.replace(
                ADMIN, "agent-a", IntStream.range(0, 65).mapToObj(i -> new AgentMemoryGrantInput("c" + i)).toList()),
            ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(agentCollections);
    }


    @Test
    @DisplayName("64개까지는 모양 검사를 지난다")
    void sixtyFourCollectionsPassTheShapeCheck() {
        when(agentCollections.requireEditableAgent("agent-a"))
            .thenThrow(new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent"));

        assertThatThrownBy(() -> service.replace(
            ADMIN, "agent-a", IntStream.range(0, 64).mapToObj(i -> new AgentMemoryGrantInput("c" + i)).toList()))
                .isInstanceOfSatisfying(
                    ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
    }


    private static List<AgentMemoryGrantInput> inputs(String... collections) {
        return Arrays.stream(collections).map(AgentMemoryGrantInput::new).toList();
    }


    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
