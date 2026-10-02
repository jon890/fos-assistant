package com.bifos.assistant.memory.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실행이 끝난 답에서 남길 사실을 제안한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemoryProposer {
    private static final int TITLE_LIMIT = 200;

    private final MemoryProposalProperties properties;
    private final MemoryService memories;
    private final HermesRunsClient hermes;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;
    private final ObjectMapper objectMapper;

    /**
     * 제안을 만들지 못하면 아무것도 만들지 않는다. 원래 대화 실행은 실패시키지 않는다.
     *
     * <p>제안 실행은 원래 실행의 agent와 대화를 이어 받아 같은 실행 기록 계통에 남긴다. 모델과 effort 도
     * 원래 실행이 해석한 값을 쓴다. 대화에 적힌 값을 다시 읽으면 단계와 에이전트 기본 모델이 빠진다.
     *
     * @param requested 원래 실행이 Hermes 에 보낸 provider, 모델, effort
     */
    public void proposeFrom(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            AgentExecution parentExecution,
            String answer,
            ModelChoice requested) {
        if (!properties.enabled()) {
            return;
        }

        AgentExecution proposalExecution = null;
        try {
            ModelChoice choice = requested == null ? ModelChoice.defaults() : requested;
            proposalExecution = executions.startInheriting(user, conversation, agent, parentExecution, choice);
            HermesRunCommand command = new HermesRunCommand(
                    agent.hermesProfile(),
                    agent.apiBaseUrl(),
                    prompt(answer),
                    null,
                    null,
                    choice.provider(),
                    choice.model(),
                    choice.reasoningEffort());
            String runId = hermes.submit(command);
            executions.attachRunId(proposalExecution, runId);
            HermesRunResult result = hermes.awaitCompletion(command, runId);
            if (result.providerBlocked()) {
                // 고른 모델의 provider 가 막힌 것은 대화 실행과 같은 코드로 남긴다. 다른 모델로 넘기지 않는다.
                AgentExecution failed =
                        executions.fail(proposalExecution, agent, result, choice, ErrorCode.PROVIDER_BLOCKED.name());
                appendFailed(failed, ErrorCode.PROVIDER_BLOCKED.name());
                return;
            }
            if (!result.succeeded()) {
                executions.fail(proposalExecution, agent, result, choice, statusOf(result));
                return;
            }
            AgentExecution completed = executions.complete(proposalExecution, agent, result, choice);
            proposalOf(result.output())
                    .ifPresent(proposal ->
                            memories.proposeUser(user, proposal.title(), proposal.content(), completed.id()));
        } catch (Exception ex) {
            if (proposalExecution != null) {
                executions.fail(proposalExecution, errorCode(ex));
            }
            log.warn("Memory 제안을 만들지 못했습니다. parentExecutionId={}", parentExecution.id(), ex);
        }
    }

    /**
     * 실패 사건을 남긴다. 제안 실행은 다른 사건을 적지 않으므로 첫 번째다.
     *
     * <p>저장이 실패해도 원래 대화에는 전하지 않는다. 사건은 관측용이다.
     */
    private void appendFailed(AgentExecution execution, String code) {
        try {
            ExecutionEvent event = eventRecorder.record(execution, ExecutionEventType.RUN_FAILED, code, 1);
            executionEvents.save(event);
        } catch (RuntimeException ex) {
            log.warn("제안 실행의 사건을 남기지 못했다 executionId={}", execution.id(), ex);
        }
    }

    private Optional<Proposal> proposalOf(String output) {
        if (output == null || output.isBlank() || "NONE".equals(output.strip())) {
            return Optional.empty();
        }
        try {
            JsonNode json = objectMapper.readTree(output);
            String title = json.path("title").asString("").strip();
            String content = json.path("content").asString("").strip();
            if (title.isEmpty() || content.isEmpty() || title.length() > TITLE_LIMIT) {
                log.warn("Memory 제안 응답의 필수 값 또는 제목 길이가 올바르지 않습니다.");
                return Optional.empty();
            }
            return Optional.of(new Proposal(title, content));
        } catch (Exception ex) {
            log.warn("Memory 제안 응답이 JSON 형식이 아닙니다.");
            return Optional.empty();
        }
    }

    private static String prompt(String answer) {
        return """
                아래 답에서 다음에도 쓸 사람에 관한 사실 하나만 골라 JSON으로 답하세요.
                남길 것은 선호, 상황, 결정처럼 다음 대화에도 쓸 사실입니다.
                남기지 마세요: 커밋 해시, 브랜치 이름, 파일 경로, 작업 진행 상황,
                이번 대화에서만 쓰고 끝나는 내용, 이미 저장된 사실과 같은 내용.
                남길 것이 없으면 정확히 NONE만 답하세요.
                JSON은 {\"title\":\"200자 이하 제목\",\"content\":\"남길 사실 하나\"}만 허용합니다.

                원래 답:
                %s
                """.formatted(answer == null ? "" : answer);
    }

    private static String statusOf(HermesRunResult result) {
        return result.status() == null ? "UNKNOWN" : result.status().toUpperCase();
    }

    private static String errorCode(Exception exception) {
        return exception instanceof ApiException api ? api.code().name() : "MEMORY_PROPOSAL_FAILED";
    }

    private record Proposal(String title, String content) {}
}
