package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.EventObservation;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceReadRequestsTest {
    private final AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
    private final ExecutionEventRepository events = mock(ExecutionEventRepository.class);
    private final SourceReadSummaries summaries = new SourceReadSummaries(executions, events);

    @BeforeEach
    void setUp() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        stubExecutions(List.of(root), List.of());
    }

    @Test
    @DisplayName("명확히 짝인 시작 주소와 가려진 성공 완료는 요청 주소만 남긴다")
    void keepsRequestedUrlForClearlyPairedRedactedCompletion() throws Exception {
        stubEvents(List.of(started(1L, 1, "https://example.com/request?q=secret"), completed(1L, 2, false, "[가림]")));
        assertThat(summary())
                .isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of("https://example.com/request")));
    }

    @Test
    @DisplayName("시작 없이 가려진 성공 완료는 요청 주소를 추측하지 않는다")
    void doesNotInferRequestedUrlWithoutStart() throws Exception {
        stubEvents(List.of(completed(1L, 1, false, "[가림]")));
        assertThat(summary()).isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of()));
    }

    @Test
    @DisplayName("완료 없이 시작만 남으면 요청 주소를 만들지 않는다")
    void doesNotExposeRequestedUrlForStartOnly() throws Exception {
        stubEvents(List.of(started(1L, 1, "https://example.com/request")));
        assertThat(summary()).isEqualTo(new SourceReadSummary(0, List.of(), 0, true, List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/[가림]", "https://example.com/read...", "https://example.com/read…"})
    @DisplayName("가려지거나 잘린 시작 주소는 가려진 성공 완료와 짝이어도 보내지 않는다")
    void rejectsRedactedOrTruncatedStartUrl(String startDetail) throws Exception {
        stubEvents(List.of(started(1L, 1, startDetail), completed(1L, 2, false, "[가림]")));
        assertThat(summary()).isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of()));
    }

    @Test
    @DisplayName("겹친 시작 묶음은 요청 주소를 만들지 않고 다음 단독 호출부터 다시 짝짓는다")
    void recoversPairingAfterOverlappingStartsClose() throws Exception {
        stubEvents(List.of(
                started(1L, 1, "https://example.com/first"),
                started(1L, 2, "https://example.com/second"),
                completed(1L, 3, false, "[가림]"),
                completed(1L, 4, false, "[가림]"),
                started(1L, 5, "https://example.com/recovered"),
                completed(1L, 6, false, "[가림]")));
        assertThat(summary())
                .isEqualTo(new SourceReadSummary(3, List.of(), 3, true, List.of("https://example.com/recovered")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("실패 또는 성공 여부 없는 완료는 열린 시작을 소비해 뒤 완료에 다시 붙이지 않는다")
    void consumesStartForFailedOrUnknownCompletion(boolean failedCompletion) throws Exception {
        Boolean failed = failedCompletion ? Boolean.TRUE : null;
        stubEvents(List.of(
                started(1L, 1, "https://example.com/consumed"),
                completed(1L, 2, failed, "[가림]"),
                completed(1L, 3, false, "[가림]")));
        assertThat(summary()).isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of()));
    }

    @Test
    @DisplayName("사건 관측이 불완전한 실행의 짝은 요청 주소를 보내지 않는다")
    void suppressesRequestedUrlForIncompleteExecution() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.INCOMPLETE);
        stubExecutions(List.of(root), List.of());
        stubEvents(List.of(started(1L, 1, "https://example.com/request"), completed(1L, 2, false, "[가림]")));
        assertThat(summary()).isEqualTo(new SourceReadSummary(1, List.of(), 1, false, List.of()));
    }

    @Test
    @DisplayName("관측한 실행의 요청 주소는 불완전한 형제가 있어도 남기고 전체 상태만 불완전으로 표시한다")
    void keepsObservedExecutionRequestWhenSiblingIsIncomplete() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        AgentExecution observedChild = execution(2L, 1L, EventObservation.OBSERVED);
        AgentExecution incompleteSibling = execution(3L, 1L, EventObservation.INCOMPLETE);
        stubExecutions(List.of(root, observedChild, incompleteSibling), List.of(observedChild, incompleteSibling));
        stubEvents(List.of(started(2L, 1, "https://example.com/request"), completed(2L, 2, false, "[가림]")));
        assertThat(summary())
                .isEqualTo(new SourceReadSummary(1, List.of(), 1, false, List.of("https://example.com/request")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"results\":[]}",
                "{\"results\":[{\"url\":\"https://example.com/error\",\"content\":\"본문\",\"error\":\"실패\"}]}",
                "{\"results\":[{\"url\":\"https://example.com/blank\",\"content\":\"   \"}]}",
                "{\"results\":[{\"blocked_by_policy\":true}]}",
                "{\"success\":false,\"results\":[]}",
                "{\"blocked_by_policy\":true,\"results\":[]}"
            })
    @DisplayName("완결된 빈 결과와 페이지 오류와 정책 차단은 시작 주소로 대체하지 않는다")
    void doesNotFallbackToRequestForExplicitNonSuccessfulResults(String detail) throws Exception {
        stubEvents(List.of(started(1L, 1, "https://example.com/request"), completed(1L, 2, false, detail)));
        assertThat(summary()).isEqualTo(new SourceReadSummary(1, List.of(), 0, true, List.of()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"results\":[false]}",
                "{\"results\":[{\"content\":\"본문\"}]}",
                "{\"results\":[{\"url\":\"https://example.com/[가림]\",\"content\":\"본문\"}]}",
                "{\"results\":[{\"url\":\"https://example.com/truncated...\",\"content\":\"본문\"}]}"
            })
    @DisplayName("결과 배열의 해석할 수 없는 항목은 명확한 요청 주소만 남긴다")
    void fallsBackToRequestForUnresolvedResultItem(String detail) throws Exception {
        stubEvents(List.of(started(1L, 1, "https://example.com/request"), completed(1L, 2, false, detail)));

        assertThat(summary())
                .isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of("https://example.com/request")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "blocked", "confirmed"})
    @DisplayName("판정 가능한 결과와 해석할 수 없는 항목이 섞여도 요청 주소로 대체하지 않는다")
    void prioritizesKnownMixedResultOverRequest(String kind) throws Exception {
        String detail = switch (kind) {
            case "error" -> "{\"results\":[{\"content\":\"본문\",\"error\":\"실패\"},false]}";
            case "blocked" -> "{\"results\":[{\"blocked_by_policy\":true},false]}";
            default -> "{\"results\":[{\"url\":\"https://example.com/confirmed\",\"content\":\"본문\"},false]}";
        };
        stubEvents(List.of(started(1L, 1, "https://example.com/request"), completed(1L, 2, false, detail)));

        assertThat(summary()).isEqualTo(new SourceReadSummary(
                1, kind.equals("confirmed") ? List.of("https://example.com/confirmed") : List.of(), 1, true, List.of()));
    }

    @Test
    @DisplayName("완결된 결과 주소가 있으면 서로 다른 시작 주소도 요청 주소로 보내지 않는다")
    void prioritizesCompletedResultOverDifferentRequestedUrl() throws Exception {
        stubEvents(List.of(
                started(1L, 1, "https://example.com/request"),
                completed(1L, 2, false, result("https://example.com/confirmed"))));
        assertThat(summary())
                .isEqualTo(new SourceReadSummary(1, List.of("https://example.com/confirmed"), 0, true, List.of()));
    }

    @Test
    @DisplayName("요청한 주소가 뒤의 완결된 결과로 확인되면 요청 주소 목록에서 뺀다")
    void removesRequestedUrlWhenLaterCompletionConfirmsSameUrl() throws Exception {
        stubEvents(List.of(
                started(1L, 1, "https://example.com/same"),
                completed(1L, 2, false, "[가림]"),
                completed(1L, 3, false, result("https://example.com/same"))));
        assertThat(summary())
                .isEqualTo(new SourceReadSummary(2, List.of("https://example.com/same"), 1, true, List.of()));
    }

    @Test
    @DisplayName("시작과 완료가 서로 다른 실행에 있으면 같은 트리여도 짝짓지 않는다")
    void doesNotPairStartAndCompletionAcrossExecutions() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        AgentExecution child = execution(2L, 1L, EventObservation.OBSERVED);
        stubExecutions(List.of(root, child), List.of(child));
        stubEvents(List.of(started(1L, 1, "https://example.com/root-request"), completed(2L, 1, false, "[가림]")));
        assertThat(summary()).isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of()));
    }

    @Test
    @DisplayName("요청 주소에서는 query와 fragment를 빼고 path만 보낸다")
    void removesQueryAndFragmentFromRequestedUrl() throws Exception {
        stubEvents(List.of(
                started(1L, 1, "https://example.com/path?access=private#section"), completed(1L, 2, false, "[가림]")));
        assertThat(summary())
                .isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of("https://example.com/path")));
    }

    @Test
    @DisplayName("손상된 성공 완료는 명확히 짝인 시작 주소를 요청 주소로 남긴다")
    void keepsRequestedUrlForMalformedCompletion() throws Exception {
        stubEvents(List.of(started(1L, 1, "https://example.com/malformed"), completed(1L, 2, false, "{\"results\":[")));
        assertThat(summary())
                .isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of("https://example.com/malformed")));
    }

    @Test
    @DisplayName("없는 성공 완료 detail은 명확히 짝인 시작 주소를 요청 주소로 남긴다")
    void keepsRequestedUrlForMissingCompletionDetail() throws Exception {
        stubEvents(List.of(started(1L, 1, "https://example.com/missing"), completed(1L, 2, false, null)));
        assertThat(summary())
                .isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of("https://example.com/missing")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/read", "https://service.internal/read", "ftp://example.com/read"})
    @DisplayName("안전하지 않은 시작 주소는 결과를 확인할 수 없어도 요청 주소로 보내지 않는다")
    void rejectsUnsafeRequestedStartUrl(String startDetail) throws Exception {
        stubEvents(List.of(started(1L, 1, startDetail), completed(1L, 2, false, "[가림]")));
        assertThat(summary()).isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of()));
    }

    @Test
    @DisplayName("서로 다른 성공 호출의 확인되지 않은 요청 주소는 발생 순서대로 한 번씩 남긴다")
    void preservesDistinctRequestedUrlsInCallOrder() throws Exception {
        stubEvents(List.of(
                started(1L, 1, "https://example.com/first"),
                completed(1L, 2, false, "[가림]"),
                started(1L, 3, "https://example.com/second"),
                completed(1L, 4, false, "[가림]")));
        assertThat(summary())
                .isEqualTo(new SourceReadSummary(
                        2, List.of(), 2, true, List.of("https://example.com/first", "https://example.com/second")));
    }

    @Test
    @DisplayName("같은 요청 주소의 확인되지 않은 성공 호출은 요청 주소 목록에서 중복 제거한다")
    void deduplicatesRepeatedRequestedUrl() throws Exception {
        stubEvents(List.of(
                started(1L, 1, "https://example.com/repeated"),
                completed(1L, 2, false, "[가림]"),
                started(1L, 3, "https://example.com/repeated#fragment"),
                completed(1L, 4, false, "[가림]")));
        assertThat(summary())
                .isEqualTo(new SourceReadSummary(2, List.of(), 2, true, List.of("https://example.com/repeated")));
    }

    @Test
    @DisplayName("관측 상태가 UNKNOWN 인 실행의 명확한 짝도 요청 주소를 보내지 않는다")
    void suppressesRequestedUrlForUnknownObservation() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.UNKNOWN);
        stubExecutions(List.of(root), List.of());
        stubEvents(List.of(started(1L, 1, "https://example.com/unknown"), completed(1L, 2, false, "[가림]")));
        assertThat(summary()).isEqualTo(new SourceReadSummary(1, List.of(), 1, false, List.of()));
    }

    @Test
    @DisplayName("다른 도구의 시작은 web_extract 완료와 짝짓지 않는다")
    void doesNotPairDifferentToolStartWithWebExtractCompletion() throws Exception {
        stubEvents(List.of(
                event(1L, 1, ExecutionEventType.TOOL_STARTED, "web_search", null, "https://example.com/search"),
                completed(1L, 2, false, "[가림]")));
        assertThat(summary()).isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of()));
    }

    @Test
    @DisplayName("서로 다른 주소의 완결된 결과가 다른 실행에 있어도 그 실행의 요청 주소를 지우지 않는다")
    void doesNotLetOtherExecutionResultRemoveRequestedUrl() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        AgentExecution child = execution(2L, 1L, EventObservation.OBSERVED);
        stubExecutions(List.of(root, child), List.of(child));
        stubEvents(List.of(
                started(1L, 1, "https://example.com/root-request"),
                completed(1L, 2, false, "[가림]"),
                completed(2L, 1, false, result("https://example.com/child-result"))));

        assertThat(summary())
                .isEqualTo(new SourceReadSummary(
                        2,
                        List.of("https://example.com/child-result"),
                        1,
                        true,
                        List.of("https://example.com/root-request")));
    }

    @Test
    @DisplayName("결과 URL이 요청 URL과 같으면 다른 실행에서 확인되어도 요청 목록에서 뺀다")
    void removesRequestedUrlWhenAnotherExecutionConfirmsSameUrl() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        AgentExecution child = execution(2L, 1L, EventObservation.OBSERVED);
        stubExecutions(List.of(root, child), List.of(child));
        stubEvents(List.of(
                started(1L, 1, "https://example.com/shared"),
                completed(1L, 2, false, "[가림]"),
                completed(2L, 1, false, result("https://example.com/shared"))));

        assertThat(summary())
                .isEqualTo(new SourceReadSummary(2, List.of("https://example.com/shared"), 1, true, List.of()));
    }

    @Test
    @DisplayName("성공 완료 뒤에 남은 시작은 다음 완료에 거꾸로 짝짓지 않는다")
    void doesNotPairStartCreatedAfterSuccessfulCompletion() throws Exception {
        stubEvents(List.of(completed(1L, 1, false, "[가림]"), started(1L, 2, "https://example.com/late-start")));

        assertThat(summary()).isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of()));
    }

    private SourceReadSummary summary() throws Exception {
        return summaries.of(List.of(answer(10L, 1L))).get(10L);
    }

    private void stubExecutions(List<AgentExecution> rows, List<AgentExecution> children) {
        doAnswer(invocation -> {
                    Collection<Long> ids = invocation.getArgument(0);
                    return rows.stream().filter(row -> ids.contains(row.id())).toList();
                })
                .when(executions)
                .findAllById(any());
        when(executions.findByRootExecutionIdIn(any())).thenReturn(children);
    }

    private void stubEvents(List<ExecutionEvent> rows) {
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any())).thenReturn(rows);
    }

    private static ChatMessage answer(Long id, Long executionId) throws Exception {
        ChatMessage answer = ChatMessage.fromAssistant(1L, "답", executionId, Instant.EPOCH);
        set(answer, "id", id);
        return answer;
    }

    private static AgentExecution execution(Long id, Long root, EventObservation observation) throws Exception {
        AgentExecution execution = AgentExecution.builder()
                .userId(1L)
                .profileName("profile")
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.EPOCH)
                .rootExecutionId(root)
                .build();
        set(execution, "id", id);
        set(execution, "parentExecutionId", root);
        set(execution, "eventObservation", observation);
        return execution;
    }

    private static ExecutionEvent started(Long executionId, int sequence, String detail) {
        return event(executionId, sequence, ExecutionEventType.TOOL_STARTED, null, detail);
    }

    private static ExecutionEvent completed(Long executionId, int sequence, Boolean failed, String detail) {
        return event(executionId, sequence, ExecutionEventType.TOOL_COMPLETED, failed, detail);
    }

    private static ExecutionEvent event(
            Long executionId, int sequence, ExecutionEventType type, Boolean failed, String detail) {
        return event(executionId, sequence, type, "web_extract", failed, detail);
    }

    private static ExecutionEvent event(
            Long executionId, int sequence, ExecutionEventType type, String toolName, Boolean failed, String detail) {
        return ExecutionEvent.builder()
                .executionId(executionId)
                .sequence(sequence)
                .eventType(type)
                .toolName(toolName)
                .failed(failed)
                .detail(detail)
                .occurredAt(Instant.EPOCH)
                .build();
    }

    private static String result(String url) {
        return "{\"results\":[{\"url\":\"" + url + "\",\"content\":\"본문\"}]}";
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
