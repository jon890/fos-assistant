package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SourceReadSummariesTest {
    private final AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
    private final ExecutionEventRepository events = mock(ExecutionEventRepository.class);
    private final SourceReadSummaries summaries = new SourceReadSummaries(executions, events);

    @Test
    @DisplayName("성공한 web_extract 완료 사건만 호출 수에 세고 안전한 URL만 보낸다")
    void countsCompletedCallsButOnlyExposesSafeSuccessfulUrls() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        stubExecutions(List.of(root), List.of());
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(List.of(
                        completed(
                                1L,
                                1,
                                "{\"results\":[{\"url\":\"https://EXAMPLE.com/a%2Fb?q=x#part\",\"content\":\"ok\"},{\"url\":\"http://localhost/x\",\"content\":\"ok\"}]}"),
                        completed(1L, 2, "{\"success\":false,\"results\":[]}"),
                        completed(1L, 3, "{\"results\":[]}")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(new SourceReadSummary(3, List.of("https://example.com/a%2Fb"), 1, true));
    }

    @Test
    @DisplayName("검색과 시작과 실패와 동명 MCP 완료는 원문 열람으로 세지 않는다")
    void excludesNonSuccessfulExactWebExtractCompletions() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        stubExecutions(List.of(root), List.of());
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(List.of(
                        event(1L, 1, ExecutionEventType.TOOL_STARTED, "web_extract", false, "{\"results\":[]}"),
                        event(1L, 2, ExecutionEventType.TOOL_COMPLETED, "web_search", false, "{\"results\":[]}"),
                        event(
                                1L,
                                3,
                                ExecutionEventType.TOOL_COMPLETED,
                                "mcp__search__web_extract",
                                false,
                                "{\"results\":[]}"),
                        event(1L, 4, ExecutionEventType.TOOL_COMPLETED, "web_extract", true, "{\"results\":[]}"),
                        event(1L, 5, ExecutionEventType.TOOL_COMPLETED, "web_extract", null, "{\"results\":[]}")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(new SourceReadSummary(0, List.of(), 0, true));
    }

    @Test
    @DisplayName("한 완료 결과의 성공 항목만 URL로 보내고 오류와 빈 내용 항목은 뺀다")
    void keepsOnlySuccessfulItemsFromMixedResults() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        stubExecutions(List.of(root), List.of());
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(
                        List.of(
                                completed(
                                        1L,
                                        1,
                                        "{\"results\":[{\"url\":\"https://example.com/kept\",\"content\":\"본문 [가림]\"},{\"url\":\"https://example.com/error\",\"content\":\"본문\",\"error\":\"실패\"},{\"url\":\"https://example.com/empty\",\"content\":\"   \"}]}")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(new SourceReadSummary(1, List.of("https://example.com/kept"), 0, true));
    }

    @Test
    @DisplayName("트리 안의 중복 URL은 정리한 첫 순서대로 한 번만 보낸다")
    void deduplicatesCleanedUrlsAcrossRootAndChild() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        AgentExecution child = execution(2L, 1L, EventObservation.OBSERVED);
        stubExecutions(List.of(root, child), List.of(child));
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(
                        List.of(
                                completed(
                                        1L,
                                        1,
                                        "{\"results\":[{\"url\":\"https://EXAMPLE.com/a?q=one\",\"content\":\"본문\"}]}"),
                                completed(
                                        2L,
                                        1,
                                        "{\"results\":[{\"url\":\"https://example.com/a#two\",\"content\":\"본문\"},{\"url\":\"https://example.com/b\",\"content\":\"본문\"}]}")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(
                        new SourceReadSummary(2, List.of("https://example.com/a", "https://example.com/b"), 0, true));
    }

    @Test
    @DisplayName("손상되거나 잘린 완료 JSON은 URL을 추측하지 않고 미해결로 센다")
    void marksMalformedJsonAsUnresolved() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        stubExecutions(List.of(root), List.of());
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(List.of(completed(
                        1L, 1, "{\"results\":[{\"url\":\"https://example.com/truncated\",\"content\":\"본문\"}")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(new SourceReadSummary(1, List.of(), 1, true));
    }

    @Test
    @DisplayName("공백뿐인 완료 detail은 예외 없이 미해결로 센다")
    void marksBlankDetailAsUnresolved() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        stubExecutions(List.of(root), List.of());
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(List.of(completed(1L, 1, "  \n\t")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(new SourceReadSummary(1, List.of(), 1, true));
    }

    @Test
    @DisplayName("완료 결과 배열의 뒤 항목이 손상돼도 앞의 안전한 URL은 보존하고 미해결로 센다")
    void preservesEarlierSafeUrlsWhenALaterResultItemIsMalformed() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        stubExecutions(List.of(root), List.of());
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(List.of(completed(
                        1L, 1, "{\"results\":[{\"url\":\"https://example.com/kept\",\"content\":\"본문\"}, false]}")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(new SourceReadSummary(1, List.of("https://example.com/kept"), 1, true));
    }

    @Test
    @DisplayName("인증 정보는 URL에서 빼고 내부이거나 가려진 URL은 보내지 않으며 미해결로 센다")
    void rejectsSensitiveInternalAndRedactedUrls() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        stubExecutions(List.of(root), List.of());
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(
                        List.of(
                                completed(
                                        1L,
                                        1,
                                        "{\"results\":[{\"url\":\"https://user:secret@example.com/path\",\"content\":\"본문\"},{\"url\":\"https://10.0.0.1/private\",\"content\":\"본문\"},{\"url\":\"https://service.internal/path\",\"content\":\"본문\"},{\"url\":\"https://example.com/[가림]\",\"content\":\"본문\"}]}")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(new SourceReadSummary(1, List.of("https://example.com/path"), 1, true));
    }

    @Test
    @DisplayName("DNS 절대 이름의 끝 점을 빼고 공개 URL로 보낸다")
    void removesTrailingDotFromDnsAbsoluteHost() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        stubExecutions(List.of(root), List.of());
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(List.of(completed(
                        1L, 1, "{\"results\":[{\"url\":\"https://EXAMPLE.com./read\",\"content\":\"본문\"}]}")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(new SourceReadSummary(1, List.of("https://example.com/read"), 0, true));
    }

    @Test
    @DisplayName("트리의 실행 하나라도 사건 관측이 불완전하면 불완전으로 표시한다")
    void marksSummaryIncompleteWhenAChildObservationIsIncomplete() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        AgentExecution child = execution(2L, 1L, EventObservation.INCOMPLETE);
        stubExecutions(List.of(root, child), List.of(child));
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(List.of(
                        completed(2L, 1, "{\"results\":[{\"url\":\"https://example.com/read\",\"content\":\"본문\"}]}")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(new SourceReadSummary(1, List.of("https://example.com/read"), 0, false));
    }

    @Test
    @DisplayName("답에 연결된 자식 실행은 그 자식과 그 아래 실행의 원문만 보낸다")
    void doesNotAttributeRootOrSiblingReadsToAnAnswerLinkedToChildExecution() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        AgentExecution child = execution(2L, 1L, EventObservation.OBSERVED);
        AgentExecution sibling = execution(3L, 1L, EventObservation.OBSERVED);
        AgentExecution descendant = execution(4L, 1L, EventObservation.OBSERVED);
        set(descendant, "parentExecutionId", 2L);
        stubExecutions(List.of(root, child, sibling, descendant), List.of(child, sibling, descendant));
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(List.of(
                        completed(1L, 1, "{\"results\":[{\"url\":\"https://example.com/root\",\"content\":\"본문\"}]}"),
                        completed(2L, 1, "{\"results\":[{\"url\":\"https://example.com/child\",\"content\":\"본문\"}]}"),
                        completed(
                                3L, 1, "{\"results\":[{\"url\":\"https://example.com/sibling\",\"content\":\"본문\"}]}"),
                        completed(
                                4L,
                                1,
                                "{\"results\":[{\"url\":\"https://example.com/descendant\",\"content\":\"본문\"}]}")));

        assertThat(summaries.of(List.of(answer(10L, 2L))).get(10L))
                .isEqualTo(new SourceReadSummary(
                        2, List.of("https://example.com/child", "https://example.com/descendant"), 0, true));
    }

    @Test
    @DisplayName("다른 사용자의 자식 실행은 URL과 관측 상태에 넣지 않는다")
    void excludesAnotherUsersChildExecution() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        AgentExecution ownChild = execution(2L, 1L, EventObservation.OBSERVED);
        AgentExecution anotherUsersChild = execution(3L, 1L, 2L, EventObservation.INCOMPLETE);
        stubExecutions(List.of(root, ownChild, anotherUsersChild), List.of(ownChild, anotherUsersChild));
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any()))
                .thenReturn(List.of(
                        completed(2L, 1, "{\"results\":[{\"url\":\"https://example.com/own\",\"content\":\"본문\"}]}"),
                        completed(
                                3L, 1, "{\"results\":[{\"url\":\"https://example.com/other\",\"content\":\"본문\"}]}")));

        assertThat(summaries.of(List.of(answer(10L, 1L))).get(10L))
                .isEqualTo(new SourceReadSummary(1, List.of("https://example.com/own"), 0, true));
    }

    @Test
    @DisplayName("여러 답의 실행과 자식과 사건을 각각 한 번의 묶음 조회로 읽는다")
    void batchesExecutionTreeAndEventQueriesForMultipleAnswers() throws Exception {
        AgentExecution firstRoot = execution(1L, null, EventObservation.OBSERVED);
        AgentExecution firstChild = execution(2L, 1L, EventObservation.OBSERVED);
        AgentExecution secondRoot = execution(10L, null, EventObservation.OBSERVED);
        AgentExecution secondChild = execution(11L, 10L, EventObservation.OBSERVED);
        stubExecutions(List.of(firstRoot, firstChild, secondRoot, secondChild), List.of(firstChild, secondChild));
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any())).thenReturn(List.of());

        assertThat(summaries.of(List.of(answer(100L, 1L), answer(101L, 10L))))
                .containsEntry(100L, new SourceReadSummary(0, List.of(), 0, true))
                .containsEntry(101L, new SourceReadSummary(0, List.of(), 0, true));

        ArgumentCaptor<Collection<Long>> executionIds = ArgumentCaptor.forClass(Collection.class);
        verify(executions, times(2)).findAllById(executionIds.capture());
        assertThat(executionIds.getAllValues())
                .allSatisfy(ids -> assertThat(ids).containsExactlyInAnyOrder(1L, 10L));
        ArgumentCaptor<Collection<Long>> rootIds = ArgumentCaptor.forClass(Collection.class);
        verify(executions).findByRootExecutionIdIn(rootIds.capture());
        assertThat(rootIds.getValue()).containsExactlyInAnyOrder(1L, 10L);
        ArgumentCaptor<Collection<Long>> eventIds = ArgumentCaptor.forClass(Collection.class);
        verify(events).findByExecutionIdInOrderByExecutionIdAscSequenceAsc(eventIds.capture());
        assertThat(eventIds.getValue()).containsExactlyInAnyOrder(1L, 2L, 10L, 11L);
    }

    @Test
    @DisplayName("사용자와 알림 줄은 요약 map에서 제외하고 비서 답만 남긴다")
    void excludesUserAndSystemMessages() throws Exception {
        AgentExecution root = execution(1L, null, EventObservation.OBSERVED);
        stubExecutions(List.of(root), List.of());
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any())).thenReturn(List.of());
        ChatMessage user = ChatMessage.fromUser(1L, 1L, "질문", Instant.EPOCH);
        ChatMessage system = ChatMessage.fromSystem(1L, "알림", Instant.EPOCH);
        set(user, "id", 8L);
        set(system, "id", 9L);

        assertThat(summaries.of(List.of(user, system, answer(10L, 1L))))
                .containsOnlyKeys(10L)
                .containsEntry(10L, new SourceReadSummary(0, List.of(), 0, true));
    }

    @Test
    @DisplayName("빈 이력은 빈 요약을 돌려준다")
    void returnsEmptySummaryForEmptyHistory() {
        assertThat(summaries.of(List.of())).isEmpty();
    }

    @Test
    @DisplayName("실행 번호가 없는 비서 답은 관측하지 못한 빈 요약을 받는다")
    void marksAssistantWithoutExecutionAsUnobserved() throws Exception {
        assertThat(summaries.of(List.of(answer(11L, null))).get(11L))
                .isEqualTo(new SourceReadSummary(0, List.of(), 0, false));
    }

    @Test
    @DisplayName("저장소에서 실행을 찾지 못한 비서 답은 관측하지 못한 빈 요약을 받는다")
    void marksAssistantWithMissingExecutionAsUnobserved() throws Exception {
        when(executions.findAllById(any())).thenReturn(List.of());

        assertThat(summaries.of(List.of(answer(11L, 99L))).get(11L))
                .isEqualTo(new SourceReadSummary(0, List.of(), 0, false));
    }

    private void stubExecutions(List<AgentExecution> rows, List<AgentExecution> children) {
        when(executions.findAllById(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return rows.stream().filter(row -> ids.contains(row.id())).toList();
        });
        when(executions.findByRootExecutionIdIn(any())).thenReturn(children);
    }

    private static ChatMessage answer(Long id, Long executionId) throws Exception {
        ChatMessage answer = ChatMessage.fromAssistant(1L, "답", executionId, Instant.EPOCH);
        set(answer, "id", id);
        return answer;
    }

    private static AgentExecution execution(Long id, Long root, EventObservation observation) throws Exception {
        return execution(id, root, 1L, observation);
    }

    private static AgentExecution execution(Long id, Long root, Long userId, EventObservation observation)
            throws Exception {
        AgentExecution execution = AgentExecution.builder()
                .userId(userId)
                .profileName("p")
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.EPOCH)
                .rootExecutionId(root)
                .build();
        set(execution, "id", id);
        set(execution, "parentExecutionId", root);
        set(execution, "eventObservation", observation);
        return execution;
    }

    private static ExecutionEvent completed(Long executionId, int sequence, String detail) {
        return event(executionId, sequence, ExecutionEventType.TOOL_COMPLETED, "web_extract", false, detail);
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

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
