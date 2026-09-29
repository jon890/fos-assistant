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
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class McpToolServiceTest {
    private final CurrentUser user = new CurrentUser(7L, "user@example.com", "사용자", 1L, UserRole.MEMBER);
    private final ArtifactWriteRequest request = new ArtifactWriteRequest(
            UUID.randomUUID(), "image.png", null, "https://images.example.com/a.png?private=value");
    private final ArtifactWriteService artifacts = mock(ArtifactWriteService.class);
    private final MemoryService memories = mock(MemoryService.class);
    private final McpToolService tools = new McpToolService(memories, artifacts);
    private final AgentExecution parent = mock(AgentExecution.class);
    private McpCaller caller;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void 로그를_받는다() {
        when(parent.id()).thenReturn(42L);
        caller = new McpCaller(user, parent, new McpCallContext("fos-root", "fos-root", "call_1"));
        logs = new ListAppender<>();
        logs.start();
        logger().addAppender(logs);
    }

    @AfterEach
    void 로그_수집을_끝낸다() {
        logger().detachAppender(logs);
    }

    @Test
    void 정해_둔_API_오류_문구와_코드만_남긴다() {
        when(artifacts.write(any(), any())).thenThrow(new ApiException(ErrorCode.INTERNAL_ERROR,
                "artifact source download timed out", new IllegalStateException("private=value")));

        assertThat(tools.writeArtifact(caller, request).get("isError")).isEqualTo(true);

        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.getFirst();
        assertThat(event.getFormattedMessage()).contains("userId=7", "executionId=42", "exceptionClass=ApiException",
                "errorCode=INTERNAL_ERROR", "reason=artifact source download timed out")
                .doesNotContain("private=value", "image.png");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void 알_수_없는_API_문구와_RuntimeException_문구는_로그에_남기지_않는다() {
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
                .contains("exceptionClass=IllegalStateException", "errorCode=INTERNAL_ERROR",
                        "reason=unexpected artifact write failure")
                .doesNotContain("private=value");
        assertThat(logs.list).allMatch(event -> event.getThrowableProxy() == null);
    }

    @Test
    void Memory_를_읽으면_부모_실행의_사용자로_읽고_실행_번호를_로그에_남긴다() {
        Memory memory = mock(Memory.class);
        when(memory.content()).thenReturn("본문");
        when(memories.bodyFor(user, 5L)).thenReturn(memory);

        assertThat(tools.readMemory(caller, 5L).get("isError")).isEqualTo(false);

        assertThat(logs.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("memory read userId=7 memoryId=5 executionId=42");
    }

    @Test
    void 옛_토큰의_호출은_실행_번호를_null_로_남긴다() {
        Memory memory = mock(Memory.class);
        when(memory.content()).thenReturn("본문");
        when(memories.bodyFor(user, 5L)).thenReturn(memory);

        tools.readMemory(new McpCaller(user, null, null), 5L);

        assertThat(logs.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("memory read userId=7 memoryId=5 executionId=null");
    }

    @Test
    void 호출_맥락_오류는_이유를_가리지_않는_한_가지_결과다() {
        assertThat(tools.invalidContext()).isEqualTo(Map.of(
                "content", List.of(Map.of("type", "text", "text", "호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.")),
                "isError", true));
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(McpToolService.class);
    }
}
