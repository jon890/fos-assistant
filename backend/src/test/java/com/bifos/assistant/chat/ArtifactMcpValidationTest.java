package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bifos.assistant.chat.artifact.application.ArtifactWriteService;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactWriteRequest;
import com.bifos.assistant.chat.artifact.domain.ArtifactSourceRejectedException;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** URL 입력 거절이 주소를 노출하지 않고 고칠 방법을 알려 주는지 검사한다. */
class ArtifactMcpValidationTest {

    @Test
    @DisplayName("주소 입력 거절은 기존 MCP 오류 결과로 원본 주소 사용을 안내한다")
    void explainsHowToCorrectSourceUrlWithoutExposingIt() {
        ArtifactWriteService artifacts = mock(ArtifactWriteService.class);
        McpCaller caller = mock(McpCaller.class);
        CurrentUser user = mock(CurrentUser.class);
        when(caller.user()).thenReturn(user);
        when(user.id()).thenReturn(1L);
        when(caller.executionId()).thenReturn(7L);
        ArtifactWriteRequest request = new ArtifactWriteRequest(
            UUID.randomUUID(), "a.png", null, "https://private.example/secret.png?token=secret");
        McpToolService service = new McpToolService(
            null, null, artifacts, null, null, null, null, null, null, null, null, null, null);
        Logger logger = (Logger)LoggerFactory.getLogger(McpToolService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (String reason : List.of("scheme", "allowed_host")) {
                appender.list.clear();
                doThrow(new ArtifactSourceRejectedException(reason)).when(artifacts).write(user, request);
                Map<String, Object> result = service.writeArtifact(caller, request);
                assertThat(result.get("isError")).isEqualTo(true);
                assertThat(result.toString()).contains("source_url", "글자 그대로")
                    .contains(reason.equals("allowed_host") ? "호스트가 허용되지 않았습니다" : "주소 형식이 올바르지 않습니다")
                    .doesNotContain("render_draft", "스티커", "private.example", "secret.png", "token=secret");
                assertThat(appender.list).hasSize(1);
                assertThat(appender.list.getFirst().getFormattedMessage()).isEqualTo(
                    "artifact source rejected userId=1 executionId=7 errorCode=VALIDATION_FAILED reason=" + reason);
                assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        ApiException syntax = new ApiException(ErrorCode.VALIDATION_FAILED, "artifact source URL is invalid");
        doThrow(syntax).when(artifacts).write(user, request);
        assertThatThrownBy(() -> service.writeArtifact(caller, request)).isSameAs(syntax);
    }
}
