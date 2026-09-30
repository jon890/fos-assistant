package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bifos.assistant.chat.application.ArtifactWriteRequest;
import com.bifos.assistant.chat.application.ArtifactWriteService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class McpToolServiceTest {
    private final CurrentUser user = new CurrentUser(7L, "user@example.com", "사용자", 1L, UserRole.MEMBER);
    private final ArtifactWriteRequest request = new ArtifactWriteRequest(
            UUID.randomUUID(), "image.png", null, "https://images.example.com/a.png?private=value");
    private final ArtifactWriteService artifacts = mock(ArtifactWriteService.class);
    private final MemoryService memories = mock(MemoryService.class);
    private final McpToolService tools = new McpToolService(memories, artifacts, mock(AgentDelegationService.class));
    private final AgentExecution parent = mock(AgentExecution.class);
    private McpCaller caller;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        when(parent.id()).thenReturn(42L);
        caller = new McpCaller(user, parent, new McpCallContext("fos-root", "fos-root", "call_1"));
        logs = new ListAppender<>();
        logs.start();
        logger().addAppender(logs);
    }

    @AfterEach
    void stopCapturingLogs() {
        logger().detachAppender(logs);
    }

    @Test
    @DisplayName("정해 둔 API 오류 문구와 코드만 남긴다")
    void keepsOnlyFixedApiErrorTextAndCode() {
        when(artifacts.write(any(), any()))
                .thenThrow(new ApiException(
                        ErrorCode.INTERNAL_ERROR,
                        "artifact source download timed out",
                        new IllegalStateException("private=value")));

        assertThat(tools.writeArtifact(caller, request).get("isError")).isEqualTo(true);

        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.getFirst();
        assertThat(event.getFormattedMessage())
                .contains(
                        "userId=7",
                        "executionId=42",
                        "exceptionClass=ApiException",
                        "errorCode=INTERNAL_ERROR",
                        "reason=artifact source download timed out")
                .doesNotContain("private=value", "image.png");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("알 수 없는 API 문구와 RuntimeException 문구는 로그에 남기지 않는다")
    void doesNotLogUnknownApiTextAndRuntimeExceptionText() {
        when(artifacts.write(any(), any()))
                .thenThrow(new ApiException(ErrorCode.INTERNAL_ERROR, "private=value"))
                .thenThrow(new IllegalStateException("private=value"));

        assertThat(tools.writeArtifact(caller, request).get("isError")).isEqualTo(true);
        assertThat(tools.writeArtifact(caller, request).get("isError")).isEqualTo(true);

        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.get(0).getFormattedMessage())
                .contains("exceptionClass=ApiException", "errorCode=INTERNAL_ERROR", "reason=artifact write failed")
                .doesNotContain("private=value");
        assertThat(logs.list.get(1).getFormattedMessage())
                .contains(
                        "exceptionClass=IllegalStateException",
                        "errorCode=INTERNAL_ERROR",
                        "reason=unexpected artifact write failure")
                .doesNotContain("private=value");
        assertThat(logs.list).allMatch(event -> event.getThrowableProxy() == null);
    }

    @Test
    @DisplayName("Memory 를 읽으면 부모 실행의 사용자로 읽고 실행 번호를 로그에 남긴다")
    void memoryReadUsesParentRunUserAndLogsRunId() {
        Memory memory = mock(Memory.class);
        when(memory.content()).thenReturn("본문");
        when(memories.bodyFor(user, 5L)).thenReturn(memory);

        assertThat(tools.readMemory(caller, 5L).get("isError")).isEqualTo(false);

        assertThat(logs.list)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("memory read userId=7 memoryId=5 executionId=42");
    }

    @Test
    @DisplayName("호출 맥락 오류는 이유를 가리지 않는 한 가지 결과다")
    void callContextErrorIsOneResultRegardlessOfReason() {
        assertThat(tools.invalidContext())
                .isEqualTo(Map.of(
                        "content",
                        List.of(Map.of("type", "text", "text", "호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.")),
                        "isError",
                        true));
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(McpToolService.class);
    }
}
