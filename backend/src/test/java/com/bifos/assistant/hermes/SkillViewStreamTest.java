package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.skill.domain.ExecutionSkillUse;
import com.bifos.assistant.skill.domain.type.SkillUseSource;
import com.bifos.assistant.skill.infra.ExecutionSkillUseRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hermes 가 보낸 {@code skill_view} 사건의 JSON 이 저장된 사건과 스킬 사용 기록에 어떻게 닿는지 끝까지 본다.
 *
 * <p>32자를 넘는 스킬 이름은 token 으로 보여 도구 내용에서 가려진다. 그래도 스킬 사용 기록에는 가리기 전에
 * 꺼내 검증한 이름이 남아야 한다. 근거는 ADR-047 에 있다.
 */
@BackendIntegrationTest
class SkillViewStreamTest {

    /** 소문자와 숫자와 붙임표로 된 44자 이름이다. 이름 규칙에는 맞고 token 가리기에는 걸린다. */
    private static final String LONG_NAME = "weekly-grocery-shopping-list-builder-2026-v2";

    private static final String HIDDEN = "[가림]";

    @Autowired
    ExecutionEventRecorder recorder;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    ExecutionSkillUseRepository skillUses;

    @Test
    @DisplayName("32자를 넘는 스킬 이름은 저장된 도구 내용에서 가리고 스킬 사용 기록에는 그 이름으로 남긴다")
    void hidesLongSkillNameInStoredDetailButRecordsSkillUseWithThatName() throws IOException {
        Long executionId = store(streamed(started("skill_view", LONG_NAME), false));

        assertThat(storedDetails(executionId))
                .singleElement()
                .satisfies(detail -> assertThat(detail).contains(HIDDEN).doesNotContain(LONG_NAME));
        assertThat(usesOf(executionId)).singleElement().satisfies(use -> {
            assertThat(use.skillName()).isEqualTo(LONG_NAME);
            assertThat(use.source()).isEqualTo(SkillUseSource.MODEL);
        });
    }

    @Test
    @DisplayName("참고 파일 경로가 붙은 미리보기에서도 긴 스킬 이름을 기록한다")
    void recordsLongSkillNameFromPreviewWithReferenceFilePath() throws IOException {
        Long executionId = store(streamed(started("skill_view", LONG_NAME + " → references/list.md"), false));

        assertThat(storedDetails(executionId))
                .singleElement()
                .satisfies(detail -> assertThat(detail).contains(HIDDEN).doesNotContain(LONG_NAME));
        assertThat(usesOf(executionId)).extracting(ExecutionSkillUse::skillName).containsExactly(LONG_NAME);
    }

    @Test
    @DisplayName("길이 상한에서 이름이 잘린 미리보기는 기록하지 않고 경로만 잘린 미리보기는 이름을 기록한다")
    void dropsPreviewTruncatedInsideNameAndRecordsWhenOnlyPathIsTruncated() throws IOException {
        Long truncatedName = store(streamed(started("skill_view", LONG_NAME.substring(0, 37) + "..."), false));
        Long truncatedPath = store(streamed(started("skill_view", LONG_NAME + " → references/li..."), false));

        assertThat(storedDetails(truncatedName)).hasSize(1);
        assertThat(usesOf(truncatedName)).isEmpty();
        assertThat(usesOf(truncatedPath))
                .extracting(ExecutionSkillUse::skillName)
                .containsExactly(LONG_NAME);
    }

    @Test
    @DisplayName("이름 규칙에 맞지 않는 미리보기는 스킬 사용으로 기록하지 않는다")
    void doesNotRecordSkillUseForPreviewBreakingNameRule() throws IOException {
        Long executionId = store(streamed(
                started("skill_view", "Weekly-Grocery-Shopping-List-Builder-2026")
                        + started("skill_view", "weekly grocery shopping")
                        + started("skill_view", "a".repeat(65))
                        + started("skill_view", "")
                        + started("skill_view", "../weekly-grocery-shopping-list-builder"),
                false));

        assertThat(storedDetails(executionId)).hasSize(5);
        assertThat(usesOf(executionId)).isEmpty();
    }

    @Test
    @DisplayName("연결용 에이전트의 실행은 도구 내용 전체를 가리고 스킬 사용을 기록하지 않는다")
    void connectorRunHidesWholeDetailAndRecordsNoSkillUse() throws IOException {
        Long executionId = store(streamed(started("skill_view", LONG_NAME) + started("skill_view", "shopping"), true));

        assertThat(storedDetails(executionId)).containsExactly("[연결 도구 내용 가림]", "[연결 도구 내용 가림]");
        assertThat(usesOf(executionId)).isEmpty();
    }

    @Test
    @DisplayName("skill view 가 아닌 도구와 도구 완료 사건은 스킬 사용을 기록하지 않는다")
    void otherToolAndCompletedEventRecordNoSkillUse() throws IOException {
        Long executionId = store(streamed(
                started("web_search", LONG_NAME)
                        + "data: {\"event\":\"tool.completed\",\"tool\":\"skill_view\",\"detail\":\"shopping\"}\n\n",
                false));

        assertThat(usesOf(executionId)).isEmpty();
    }

    private static String started(String tool, String preview) {
        return "data: {\"event\":\"tool.started\",\"tool\":\"" + tool + "\",\"preview\":\"" + preview + "\"}\n\n";
    }

    /** 이 SSE 본문을 Hermes 가 보낸 것처럼 실제 스트림 읽기로 읽는다. */
    private static List<RunEvent> streamed(String body, boolean connectorManaged) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/runs/run-one/events", exchange -> {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, data.length);
            try (var response = exchange.getResponseBody()) {
                response.write(data);
            }
        });
        server.start();
        try {
            HermesProfileKeyStore keys = mock(HermesProfileKeyStore.class);
            when(keys.resolve("test-profile")).thenReturn("test-key");
            HermesRunEventStream stream = new HermesRunEventStream(
                    keys,
                    new HermesProperties(null, null, null, null, null, null, null, null),
                    JsonMapper.builder().build());
            List<RunEvent> events = new ArrayList<>();
            stream.open(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-profile",
                    "run-one",
                    events::add,
                    opened -> {},
                    connectorManaged ? ToolDetailScope.ALL : ToolDetailScope.NONE);
            return events;
        } finally {
            server.stop(0);
        }
    }

    /** 새 실행 하나를 만들고, 대화 경로가 하듯 사건을 옮겨 적어 저장한다. 그 실행의 번호를 낸다. */
    private Long store(List<RunEvent> events) {
        AgentExecution execution = executions.save(AgentExecution.builder()
                .userId(1L)
                .profileName("dad")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.parse("2026-09-18T00:00:00Z"))
                .build());
        int sequence = 1;
        for (RunEvent event : events) {
            ExecutionEvent stored = recorder.record(execution, event, sequence);
            if (stored != null) {
                executionEvents.save(stored);
                sequence++;
            }
        }
        return execution.id();
    }

    private List<String> storedDetails(Long executionId) {
        return executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId)).stream()
                .filter(event -> event.eventType() == ExecutionEventType.TOOL_STARTED)
                .map(ExecutionEvent::detail)
                .toList();
    }

    private List<ExecutionSkillUse> usesOf(Long executionId) {
        return skillUses.findByExecutionIdInOrderByExecutionIdAscSkillNameAsc(List.of(executionId));
    }
}
