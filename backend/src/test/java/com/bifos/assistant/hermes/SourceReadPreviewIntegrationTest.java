package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.application.SourceReadSummaries;
import com.bifos.assistant.chat.application.SourceReadSummary;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.skill.application.SkillUseRecorder;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.EventObservation;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SourceReadPreviewIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    @Test
    @DisplayName("가려진 긴 원문 미리보기는 요청 URL만 남기고 결과 URL은 남기지 않는다")
    void keepsOnlyRequestedUrlFromRedactedTruncatedPreview() throws Exception {
        String full = result("https://example.com/hidden", "example ".repeat(125));
        String preview = full.substring(0, 497) + "...";
        RunEvent started = HermesRunEventStream.toRunEvent(started("https://example.com/hidden"));
        RunEvent runEvent = HermesRunEventStream.toRunEvent(event(preview));
        List<ExecutionEvent> saved = records(started, runEvent);

        assertThat(preview).hasSize(500).endsWith("...");
        assertThat(runEvent.detail()).isEqualTo("[가림]");
        assertThat(saved.get(1).failed()).isFalse();
        assertThat(saved.get(1).detail()).isEqualTo("[가림]");
        assertThat(summary(saved)).isEqualTo(new SourceReadSummary(1, List.of(), 1, true, List.of("https://example.com/hidden")));
    }

    @Test
    @DisplayName("온전한 원문 미리보기는 같은 저장 경로에서 공개 URL을 보낸다")
    void exposesUrlFromCompletePreview() throws Exception {
        RunEvent runEvent = HermesRunEventStream.toRunEvent(
                event(result("https://example.com/visible", "short content")));
        List<ExecutionEvent> saved = records(HermesRunEventStream.toRunEvent(started("https://example.com/visible")), runEvent);

        assertThat(saved.get(1).failed()).isFalse();
        assertThat(summary(saved)).isEqualTo(new SourceReadSummary(1, List.of("https://example.com/visible"), 0, true, List.of()));
    }

    private static JsonNode event(String preview) throws Exception {
        return JSON.readTree("""
                {"event":"tool.completed","tool":"web_extract","error":false,"preview":%s}
                """.formatted(JSON.writeValueAsString(preview)));
    }

    private static JsonNode started(String preview) throws Exception {
        return JSON.readTree("{\"event\":\"tool.started\",\"tool\":\"web_extract\",\"preview\":\""
                + preview
                + "\"}");
    }

    private static String result(String url, String content) throws Exception {
        return JSON.writeValueAsString(JSON.readTree("""
                {"results":[{"url":%s,"title":"example title","content":%s,"error":null}]}
                """.formatted(JSON.writeValueAsString(url), JSON.writeValueAsString(content))));
    }

    private static List<ExecutionEvent> records(RunEvent... events) throws Exception {
        ExecutionEventRecorder recorder = new ExecutionEventRecorder(mock(SkillUseRecorder.class), CLOCK);
        AgentExecution execution = execution();
        return IntStream.range(0, events.length)
                .mapToObj(index -> recorder.record(execution, events[index], index + 1))
                .toList();
    }

    private static SourceReadSummary summary(List<ExecutionEvent> event) throws Exception {
        AgentExecution execution = execution();
        AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
        ExecutionEventRepository events = mock(ExecutionEventRepository.class);
        when(executions.findAllById(any())).thenReturn(List.of(execution));
        when(executions.findByRootExecutionIdIn(any())).thenReturn(List.of());
        when(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(any())).thenReturn(event);
        ChatMessage answer = ChatMessage.fromAssistant(1L, "답", execution.id(), Instant.EPOCH);
        set(answer, "id", 10L);

        return new SourceReadSummaries(executions, events).of(List.of(answer)).get(answer.id());
    }

    private static AgentExecution execution() throws Exception {
        AgentExecution execution = AgentExecution.builder()
                .userId(1L)
                .profileName("profile")
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.EPOCH)
                .build();
        set(execution, "id", 1L);
        set(execution, "eventObservation", EventObservation.OBSERVED);
        return execution;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
