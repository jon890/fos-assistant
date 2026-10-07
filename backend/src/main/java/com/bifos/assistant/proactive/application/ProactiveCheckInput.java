package com.bifos.assistant.proactive.application;

import static com.bifos.assistant.proactive.application.ProactiveCheckRun.NO_RECENT_FINDINGS;
import static com.bifos.assistant.proactive.application.ProactiveCheckRun.NO_RECENT_PROBLEMS;
import static com.bifos.assistant.proactive.application.ProactiveCheckRun.OPENING;

import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;

/** 변화 신호와 최근 발견·문제 후보를 살펴보기 입력으로 조립한다. */
class ProactiveCheckInput {
    private static final String UNKNOWN = "모름";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final CurrentUser owner;
    private final Long agentId;
    private final ProactiveCheck check;
    private final ProactiveCheckRun.Deps deps;

    ProactiveCheckInput(CurrentUser owner, Long agentId, ProactiveCheck check, ProactiveCheckRun.Deps deps) {
        this.owner = owner;
        this.agentId = agentId;
        this.check = check;
        this.deps = deps;
    }

    String buildInput(Instant now) {
        Long conversationId = check.conversationId();
        StringBuilder text =
                new StringBuilder(OPENING).append("\n\n지금 시각: ").append(now).append("\n\n변화 신호\n");
        Optional<ProactiveCheck> last = deps.checks()
                .findFirstByConversationIdAndStatusNotAndSkippedReasonIsNullOrderByIdDesc(
                        conversationId, CheckStatus.RUNNING);
        if (last.isEmpty()) {
            text.append("- 지난 살펴보기: 처음\n- 그 뒤 사용자가 이 대화에 보낸 메시지: ")
                    .append(UNKNOWN)
                    .append("\n- Memory 문맥이 지난 살펴보기와 같은지: ")
                    .append(UNKNOWN);
        } else {
            ProactiveCheck previous = last.get();
            Instant endedAt = previous.finishedAt();
            text.append("- 지난 살펴보기: ")
                    .append(endedAt == null ? previous.startedAt() : endedAt)
                    .append("\n- 그 뒤 사용자가 이 대화에 보낸 메시지: ")
                    .append(userMessagesAfter(endedAt))
                    .append("\n- Memory 문맥이 지난 살펴보기와 같은지: ")
                    .append(memorySignal(previous));
        }
        text.append("\n\n최근에 알린 발견\n").append(recentFindings(now));
        text.append("\n\n최근에 받아들인 문제 후보\n").append(recentProblems(now));
        return text.toString();
    }

    /** 그 시각 뒤 사용자가 점검 대화에 보낸 메시지 수. 시각이 없으면 「모름」 이다. */
    private String userMessagesAfter(Instant after) {
        if (after == null) {
            return UNKNOWN;
        }
        return deps.messages()
                        .countByConversationIdAndRoleAndCreatedAtAfter(check.conversationId(), MessageRole.USER, after)
                + "개";
    }

    /**
     * 지난 살펴보기의 루트 실행 줄에 적힌 문맥 지문과 이번 문맥의 지문을 견준다. 이번 지문은 실행 줄을 만들 때와 같은 두 메서드로 조립해
     * 구한다. 어느 쪽이든 없으면 「모름」 이다.
     */
    private String memorySignal(ProactiveCheck previous) {
        if (previous.rootExecutionId() == null) {
            return UNKNOWN;
        }
        String before = deps.executions()
                .findById(previous.rootExecutionId())
                .map(AgentExecution::instructionsHash)
                .orElse(null);
        String current = deps.contextAssembler()
                .withResponseInstructions(deps.contextAssembler().assemble(owner, agentId))
                .instructionsHash();
        if (before == null || current == null) {
            return UNKNOWN;
        }
        return before.equals(current) ? "같음" : "바뀜";
    }

    /**
     * 최근에 알린 발견을 한 줄씩 적고 {@code <external-data>} 로 감싼다. 모델이 쓴 글에서 온 것이기 때문이다.
     *
     * <p>메시지 수는 그 발견을 낸 살펴보기가 끝난 뒤부터 센다. 같은 살펴보기의 발견은 한 번만 센다. 그 살펴보기가 끝난 시각이 없으면(서버가
     * 도중에 내려가 {@code RUNNING} 으로 남은 줄) 「모름」 이다.
     */
    private String recentFindings(Instant now) {
        List<ProactiveCheckFinding> recent = deps.findings()
                .findByConversationIdAndKindAndCreatedAtAfterOrderByIdDesc(
                        check.conversationId(),
                        FindingKind.NEW,
                        now.minus(deps.properties().digestWindow()),
                        PageRequest.ofSize(deps.properties().digestMaxItems()));
        if (recent.isEmpty()) {
            return NO_RECENT_FINDINGS;
        }
        Map<Long, ProactiveCheck> checksById =
                deps
                        .checks()
                        .findAllById(recent.stream()
                                .map(ProactiveCheckFinding::checkId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(ProactiveCheck::id, Function.identity()));
        Map<Long, String> countsByCheck = new HashMap<>();
        String lines = recent.stream()
                .map(finding -> "- [" + finding.area() + "] " + orDash(finding.topicKey())
                        + " · " + orDash(finding.title())
                        + " · " + orDash(finding.sourceUrl())
                        + " · 확인 " + (finding.checkedAt() == null ? UNKNOWN : DATE.format(finding.checkedAt()))
                        + " · 그 뒤 사용자 메시지 "
                        + countsByCheck.computeIfAbsent(
                                finding.checkId(),
                                id -> userMessagesAfter(Optional.ofNullable(checksById.get(id))
                                        .map(ProactiveCheck::finishedAt)
                                        .orElse(null))))
                .collect(Collectors.joining("\n"));
        return ExternalData.wrap(lines);
    }

    /** 최근에 받아들인 문제 후보를 한 줄씩 적고 {@code <external-data>} 로 감싼다. 모델이 쓴 글에서 온 것이기 때문이다. */
    private String recentProblems(Instant now) {
        List<ProactiveCheckProblem> recent = deps.problems()
                .findByConversationIdAndStatusAndCreatedAtAfterOrderByIdDesc(
                        check.conversationId(),
                        ProblemStatus.ACCEPTED,
                        now.minus(deps.properties().digestWindow()),
                        PageRequest.ofSize(deps.properties().digestMaxItems()));
        if (recent.isEmpty()) {
            return NO_RECENT_PROBLEMS;
        }
        String lines = recent.stream()
                .map(problem -> "- " + orDash(problem.problemKey()) + " · " + orDash(problem.problem()))
                .collect(Collectors.joining("\n"));
        return ExternalData.wrap(lines);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
