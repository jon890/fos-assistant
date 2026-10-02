package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.skill.domain.type.SkillUseSource;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.user.domain.type.UserRole;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 엔티티에 {@code @Enumerated} 로 저장되는 enum 의 상수 이름과 위치를 고정한다.
 *
 * <p>상수 이름은 DB 에 저장된다. 이름을 바꾸거나 빼려면 마이그레이션을 함께 판단한다.
 * 패키지 이름은 저장되지 않으므로 {@code domain.type} 으로 옮겨도 저장 값은 같다.
 */
class StoredEnumNamesTest {

    private static final Map<Class<? extends Enum<?>>, List<String>> STORED_ENUMS = Map.of(
            CostMode.class, List.of("SUBSCRIPTION", "API"),
            CredentialScope.class, List.of("SHARED_HOUSEHOLD", "DEDICATED"),
            AgentVisibility.class, List.of("PRIVATE", "GROUP"),
            MessageRole.class, List.of("USER", "ASSISTANT", "SYSTEM"),
            SkillUseSource.class, List.of("COMMAND", "MODEL"),
            UserRole.class, List.of("ADMIN", "MEMBER"),
            ExecutionStatus.class, List.of("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"),
            ExecutionEventType.class,
                    List.of(
                            "RUN_STARTED",
                            "RUN_COMPLETED",
                            "RUN_CANCELLED",
                            "RUN_FAILED",
                            "TOOL_STARTED",
                            "TOOL_COMPLETED",
                            "SUBAGENT_STARTED",
                            "SUBAGENT_COMPLETED",
                            "PROVIDER_SWITCHED"));

    @Test
    @DisplayName("저장되는 enum 의 상수 이름이 순서까지 옮기기 전과 같다")
    void constantNamesAreUnchanged() {
        STORED_ENUMS.forEach((type, expected) -> {
            List<String> actual =
                    Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
            assertThat(actual).as(type.getSimpleName()).containsExactlyElementsOf(expected);
        });
    }

    @Test
    @DisplayName("저장되는 enum 은 모두 domain.type 패키지에 있다")
    void storedEnumsLiveInDomainType() {
        List<String> misplaced = STORED_ENUMS.keySet().stream()
                .filter(type -> !type.getPackageName().endsWith(".domain.type"))
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        assertThat(misplaced).as("domain.type 밖에 있는 타입").isEmpty();
    }
}
