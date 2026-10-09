package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.InspectedImage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class AttachmentInspectServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private final McpCallerResolver callers = mock(McpCallerResolver.class);
    private final AttachmentService attachments = mock(AttachmentService.class);
    private final AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
    private final AgentExecution execution = mock(AgentExecution.class);
    private final McpPrincipal principal = new McpPrincipal(1L, "demo", "key");
    private final McpCallContext context = new McpCallContext("root", "session", "actual-call");
    private final CurrentUser user = new CurrentUser(1L, "test@example.test", "사용자", 1L, UserRole.MEMBER);
    private final AttachmentInspectService service = new AttachmentInspectService(callers, attachments, executions,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(execution.id()).thenReturn(1L);
        when(execution.rootExecutionId()).thenReturn(null);
        when(execution.conversationId()).thenReturn(2L);
        when(execution.startedAt()).thenReturn(NOW.minusSeconds(1));
        when(execution.status()).thenReturn(ExecutionStatus.RUNNING);
        when(callers.resolveAttachmentInspection(eq(principal), any(), eq(true)))
                .thenReturn(new McpCaller(user, execution, context));
        when(executions.findById(1L)).thenReturn(Optional.of(execution));
        when(executions.existsByIdAndStatus(1L, ExecutionStatus.RUNNING)).thenAnswer(invocation -> execution.status() == ExecutionStatus.RUNNING);
        when(attachments.inspect(eq(user), eq(2L), eq(7L), eq(null), any())).thenReturn(new InspectedImage("image/png", new byte[] {1}));
    }

    private ObjectNode request(Instant issued) {
        return request(issued, 7, "actual-call");
    }

    private ObjectNode request(Instant issued, long attachmentId, String call) {
        ObjectNode body = JsonMapper.builder().build().createObjectNode().put("attachment_id", attachmentId);
        body.putObject("_fos_ctx").put("v", 1);
        McpCallContext selected = new McpCallContext("root", "session", call);
        when(callers.resolveAttachmentInspection(eq(principal), any(), eq(true)))
                .thenReturn(new McpCaller(user, execution, selected));
        String signed = String.join("\n", "v1-attachment-inspect", "root", "session", call,
                Long.toString(issued.toEpochMilli()), AttachmentInspectRequest.from(body).digest(), "1");
        body.putObject("_fos_inspect").put("issued_at_ms", issued.toEpochMilli()).put("top_level", true)
                .put("sig", HexFormat.of().formatHex(McpCallContext.hmac(principal.tokenHash(), signed)));
        return body;
    }

    @Test
    @org.junit.jupiter.api.DisplayName("현재 실행은 지난 사진을 읽고 옛 서명은 다음 실행에서 거절한다")
    void currentExecutionCanReadPastAttachmentButOldProofCannotCrossTurn() {
        assertThat(service.inspect(principal, request(NOW)).bytes()).containsExactly(1);
        when(execution.startedAt()).thenReturn(NOW.plusNanos(1));
        assertThatThrownBy(() -> service.inspect(principal, request(NOW))).isInstanceOf(ApiException.class);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("끝난 실행과 바뀐 인자 및 만료되거나 미래인 서명을 거절한다")
    void rejectsTerminalExecutionAndChangedDigestAndExpiredOrFutureProof() {
        for (ExecutionStatus status : ExecutionStatus.values()) {
            if (status != ExecutionStatus.RUNNING) {
                when(execution.status()).thenReturn(status);
                assertThatThrownBy(() -> service.inspect(principal, request(NOW))).isInstanceOf(ApiException.class);
            }
        }
        when(execution.status()).thenReturn(ExecutionStatus.RUNNING);
        ObjectNode changed = request(NOW).put("attachment_id", 8);
        assertThatThrownBy(() -> service.inspect(principal, changed)).isInstanceOf(ApiException.class);
        for (Instant issued : new Instant[] {NOW.minusSeconds(2), NOW.plusMillis(1), NOW.minusSeconds(61)}) {
            assertThatThrownBy(() -> service.inspect(principal, request(issued))).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(attachments);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("같은 도구 호출의 반복 조회를 제한한다")
    void limitsRepeatedCallAndRejectsCancellationDuringRead() {
        ObjectNode body = request(NOW);
        service.inspect(principal, body);
        service.inspect(principal, body);
        assertThatThrownBy(() -> service.inspect(principal, body)).isInstanceOf(ApiException.class);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("읽는 동안 취소된 실행에는 사진을 반환하지 않는다")
    void cancellationDuringReadDoesNotReturnPixels() {
        when(attachments.inspect(eq(user), eq(2L), eq(7L), eq(null), any())).thenAnswer(invocation -> {
            when(execution.status()).thenReturn(ExecutionStatus.CANCELLED);
            return new InspectedImage("image/png", new byte[] {1});
        });
        assertThatThrownBy(() -> service.inspect(principal, request(NOW))).isInstanceOf(ApiException.class);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("30장을 각각 두 번 조회할 수 있고 한 사진의 반복은 제한한다")
    void thirtyPhotosCanAllBeReadTwiceAndPerPhotoRepeatsAreBounded() {
        when(attachments.inspect(eq(user), eq(2L), any(), eq(null), any()))
                .thenReturn(new InspectedImage("image/png", new byte[] {1}));
        for (long attachmentId = 1; attachmentId <= 30; attachmentId++) {
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThat(service.inspect(principal, request(NOW, attachmentId, "call-" + attachmentId + "-" + attempt)).bytes())
                        .containsExactly(1);
            }
        }
        service.inspect(principal, request(NOW, 1, "third"));
        assertThatThrownBy(() -> service.inspect(principal, request(NOW, 1, "fourth"))).isInstanceOf(ApiException.class);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("최상위 증명이 변조되면 현재 실행을 고르지 못한다")
    void tamperedTopLevelProofCannotSelectCurrentExecution() {
        ObjectNode body = request(NOW);
        ((ObjectNode) body.get("_fos_inspect")).put("top_level", false);
        when(callers.resolveAttachmentInspection(eq(principal), any(), eq(false)))
                .thenReturn(new McpCaller(user, execution, context));
        assertThatThrownBy(() -> service.inspect(principal, body)).isInstanceOf(ApiException.class);
        verifyNoInteractions(attachments);
    }
}
