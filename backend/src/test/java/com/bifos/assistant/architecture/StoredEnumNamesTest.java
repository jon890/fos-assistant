package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.skill.domain.type.SkillUseSource;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
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

    private static final Map<Class<? extends Enum<?>>, List<String>> STORED_ENUMS = Map.ofEntries(
            Map.entry(CostMode.class, List.of("SUBSCRIPTION", "API")),
            Map.entry(CredentialScope.class, List.of("SHARED_HOUSEHOLD", "DEDICATED")),
            Map.entry(AgentVisibility.class, List.of("PRIVATE", "GROUP")),
            Map.entry(MessageRole.class, List.of("USER", "ASSISTANT", "SYSTEM")),
            Map.entry(ConversationPurpose.class, List.of("CHAT", "CHECK")),
            Map.entry(SkillUseSource.class, List.of("COMMAND", "MODEL")),
            Map.entry(UserRole.class, List.of("ADMIN", "MEMBER")),
            Map.entry(ExecutionStatus.class, List.of("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")),
            Map.entry(ModelTier.class, List.of("FAST", "BALANCED", "DEEP")),
            Map.entry(
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
                            "PROVIDER_SWITCHED")),
            Map.entry(CheckTrigger.class, List.of("MANUAL", "SCHEDULED")),
            Map.entry(CheckStatus.class, List.of("RUNNING", "SUCCEEDED", "FAILED", "STOPPED")),
            Map.entry(CheckOutcome.class, List.of("FINDINGS", "NOTHING_NEW", "INVALID_RESULT")),
            Map.entry(FindingKind.class, List.of("NEW", "REFERENCE")),
            Map.entry(
                    FindingReason.class,
                    List.of(
                            "NO_SOURCE",
                            "NOT_CHECKED_NOW",
                            "CLOSED",
                            "STALE",
                            "FRESHNESS_UNKNOWN",
                            "INCOMPLETE",
                            "REPEATED")));

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
