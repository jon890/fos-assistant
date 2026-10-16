package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ArtifactWriteRequest;
import com.bifos.assistant.chat.application.ArtifactWriteService;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.embedding.EmbeddingUnavailableException;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.application.model.MemorySearchCursor;
import com.bifos.assistant.memory.application.model.MemorySearchPage;
import com.bifos.assistant.memory.application.relevance.MemoryRelevance;
import com.bifos.assistant.memory.application.relevance.QueryEmbedder;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionContextSourceWriter;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class McpToolServiceTest {
    private final CurrentUser user = new CurrentUser(7L, "user@example.com", "사용자", 1L, UserRole.MEMBER);
    private final ArtifactWriteRequest request = new ArtifactWriteRequest(
        UUID.randomUUID(), "image.png", null, "https://images.example.com/a.png?private=value");
    private final ArtifactWriteService artifacts = mock(ArtifactWriteService.class);
    private final MemoryService memories = mock(MemoryService.class);
    private final McpToolService tools = new McpToolService(
        memories,
        QueryEmbedder.disabled(),
        artifacts,
        mock(AgentDelegationService.class),
        mock(ExecutionDeliveryWriter.class),
        mock(ExecutionContextSourceWriter.class),
        mock(AgentRepository.class),
        mock(AgentConnectorBindings.class),
        mock(FollowUpService.class),
        mock(McpMemoryRemember.class),
        mock(McpMemoryLink.class),
        mock(McpToolCatalog.class),
        Clock.systemUTC());
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
    @DisplayName("기억 검색은 질문 임베딩을 먼저 만들어 넘기고 임베딩이 실패하면 빈 벡터로 키워드 검색을 한다")
    void embedsQuestionBeforeSearchAndFallsBackOnFailure() {
        MemoryEmbeddingProperties enabled = new MemoryEmbeddingProperties("http://embedding.test", "test-model", null, null, null, null, null, null);
        when(memories.searchFor(any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(new MemorySearchPage(List.of(), null, MemoryRelevance.SEARCH_HYBRID));
        MemorySearchCursor cursor = MemorySearchCursor.first("바퀴 언제 바꿨지");

        toolsWith(new QueryEmbedder(enabled, (inputs, timeout) -> List.of(new float[] {1f, 0f})))
            .searchMemory(caller, "바퀴 언제 바꿨지", 10, cursor);
        toolsWith(new QueryEmbedder(enabled, (inputs, timeout) -> {
            throw new EmbeddingUnavailableException("fake failure");
        })).searchMemory(caller, "바퀴 언제 바꿨지", 10, cursor);

        ArgumentCaptor<Optional<float[]>> vectors = ArgumentCaptor.captor();
        verify(memories, times(2)).searchFor(any(), any(), eq("바퀴 언제 바꿨지"), eq(10), eq(cursor), vectors.capture());
        assertThat(vectors.getAllValues().get(0)).hasValueSatisfying(vector -> assertThat(vector).containsExactly(1f, 0f));
        assertThat(vectors.getAllValues().get(1)).isEmpty();
    }


    @Test
    @DisplayName("키워드만으로 매긴 검색의 다음 페이지는 질문을 임베딩하지 않는다")
    void skipsQueryEmbeddingForKeywordContinuation() {
        MemoryEmbeddingProperties enabled = new MemoryEmbeddingProperties("http://embedding.test", "test-model", null, null, null, null, null, null);
        when(memories.searchFor(any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(new MemorySearchPage(List.of(), null, MemoryRelevance.SEARCH_KEYWORD));
        List<String> embedded = new ArrayList<>();
        MemorySearchCursor keywordNext = new MemorySearchCursor(MemorySearchCursor.fingerprintOf("바퀴"), 10, false);

        toolsWith(new QueryEmbedder(enabled, (inputs, timeout) -> {
            embedded.addAll(inputs);
            return List.of(new float[] {1f});
        })).searchMemory(caller, "바퀴", 10, keywordNext);

        assertThat(embedded).isEmpty();
        verify(memories).searchFor(any(), any(), eq("바퀴"), eq(10), eq(keywordNext), eq(Optional.empty()));
    }


    private McpToolService toolsWith(QueryEmbedder embedder) {
        return new McpToolService(
            memories,
            embedder,
            artifacts,
            mock(AgentDelegationService.class),
            mock(ExecutionDeliveryWriter.class),
            mock(ExecutionContextSourceWriter.class),
            mock(AgentRepository.class),
            mock(AgentConnectorBindings.class),
            mock(FollowUpService.class),
            mock(McpMemoryRemember.class),
            mock(McpMemoryLink.class),
            mock(McpToolCatalog.class),
            Clock.systemUTC());
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
        when(memories.contentOf(memory)).thenReturn("본문");
        when(memories.bodyFor(eq(user), any(), eq(5L))).thenReturn(memory);

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
        return (Logger)LoggerFactory.getLogger(McpToolService.class);
    }
}
