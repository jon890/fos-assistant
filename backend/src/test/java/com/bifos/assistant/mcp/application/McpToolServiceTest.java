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
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
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
    private final McpToolService tools = new McpToolService(mock(MemoryService.class), artifacts);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void 로그를_받는다() {
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

        assertThat(tools.writeArtifact(user, request).get("isError")).isEqualTo(true);

        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.getFirst();
        assertThat(event.getFormattedMessage()).contains("userId=7", "exceptionClass=ApiException",
                "errorCode=INTERNAL_ERROR", "reason=artifact source download timed out")
                .doesNotContain("private=value", "image.png");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void 알_수_없는_API_문구와_RuntimeException_문구는_로그에_남기지_않는다() {
        when(artifacts.write(any(), any()))
                .thenThrow(new ApiException(ErrorCode.INTERNAL_ERROR, "private=value"))
                .thenThrow(new IllegalStateException("private=value"));

        assertThat(tools.writeArtifact(user, request).get("isError")).isEqualTo(true);
        assertThat(tools.writeArtifact(user, request).get("isError")).isEqualTo(true);

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

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(McpToolService.class);
    }
}
