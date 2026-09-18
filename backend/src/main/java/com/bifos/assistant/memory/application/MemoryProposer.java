package com.bifos.assistant.memory.application;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실행이 끝난 답에서 남길 사실을 제안한다. */
@Service
@RequiredArgsConstructor
public class MemoryProposer {

    private static final Logger log = LoggerFactory.getLogger(MemoryProposer.class);
    private static final int TITLE_LIMIT = 200;

    private final MemoryProposalProperties properties;
    private final MemoryService memories;
    private final HermesRunsClient hermes;
    private final AgentModelSelector modelSelector;
    private final ExecutionRecorder executions;
    private final ObjectMapper objectMapper;

    /**
     * 제안을 만들지 못하면 아무것도 만들지 않는다. 원래 대화 실행은 실패시키지 않는다.
     *
     * <p>제안 실행은 원래 실행의 agent와 대화를 이어 받아 같은 실행 기록 계통에 남긴다.
     */
    public void proposeFrom(CurrentUser user, Conversation conversation, Agent agent,
            AgentExecution parentExecution, String answer) {
        if (!properties.enabled()) return;

        AgentExecution proposalExecution = null;
        try {
            // 제안 실행도 그 에이전트가 정한 1순위를 쓴다. 쓸 수 있는 것이 없으면 만들지 않는다.
            ModelOption option = modelSelector.availableFor(agent).stream()
                    .findFirst()
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.NO_MODEL_AVAILABLE, "this agent has no model it can use right now"));
            proposalExecution = executions.start(user, conversation, agent,
                    parentExecution.id(), parentExecution.id(),
                    ExecutionContextSnapshot.ofChars(0L), option, null);
            HermesRunCommand command = new HermesRunCommand(agent.hermesProfile(), agent.apiBaseUrl(),
                    prompt(answer), null, null, option.provider(), option.model());
            String runId = hermes.submit(command);
            executions.attachRunId(proposalExecution, runId);
            HermesRunResult result = hermes.awaitCompletion(command, runId);
            if (!result.succeeded()) {
                executions.fail(proposalExecution, statusOf(result));
                return;
            }
            AgentExecution completed = executions.complete(proposalExecution, agent, result, option);
            proposalOf(result.output()).ifPresent(proposal ->
                    memories.proposeUser(user, proposal.title(), proposal.content(), completed.id()));
        } catch (Exception ex) {
            if (proposalExecution != null) {
                executions.fail(proposalExecution, errorCode(ex));
            }
            log.warn("Memory 제안을 만들지 못했습니다. parentExecutionId={}", parentExecution.id(), ex);
        }
    }

    private java.util.Optional<Proposal> proposalOf(String output) {
        if (output == null || output.isBlank() || "NONE".equals(output.strip())) return java.util.Optional.empty();
        try {
            JsonNode json = objectMapper.readTree(output);
            String title = json.path("title").asString("").strip();
            String content = json.path("content").asString("").strip();
            if (title.isEmpty() || content.isEmpty() || title.length() > TITLE_LIMIT) {
                log.warn("Memory 제안 응답의 필수 값 또는 제목 길이가 올바르지 않습니다.");
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Proposal(title, content));
        } catch (Exception ex) {
            log.warn("Memory 제안 응답이 JSON 형식이 아닙니다.");
            return java.util.Optional.empty();
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
        return exception instanceof com.bifos.assistant.shared.error.ApiException api
                ? api.code().name() : "MEMORY_PROPOSAL_FAILED";
    }

    private record Proposal(String title, String content) {
    }
}
