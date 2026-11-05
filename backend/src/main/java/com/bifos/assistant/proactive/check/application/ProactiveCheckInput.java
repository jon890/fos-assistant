package com.bifos.assistant.proactive.check.application;

import static com.bifos.assistant.proactive.check.application.ProactiveCheckRun.EVENT_OPENING;
import static com.bifos.assistant.proactive.check.application.ProactiveCheckRun.NO_RECENT_REPORTED;
import static com.bifos.assistant.proactive.check.application.ProactiveCheckRun.NO_RECENT_SUGGESTIONS;
import static com.bifos.assistant.proactive.check.application.ProactiveCheckRun.OPENING;

import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.followup.application.dto.RecentSuggestion;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.proactive.check.config.ProactiveCheckProperties;
import com.bifos.assistant.proactive.check.application.dto.EventBatch;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 변화 신호와 기억에서 꺼낸 최근 일, 그 점검 대화의 최근 제안과 최근 보고가 알린 것을 살펴보기 입력으로 조립한다. */
class ProactiveCheckInput {
    private static final String UNKNOWN = "모름";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    /** 입력에 싣는 제안 줄의 상태 낱말이다. */
    private static final Map<FollowUpStatus, String> STATUS_LABELS = new EnumMap<>(Map.of(
        FollowUpStatus.PROPOSED, "제안됨",
        FollowUpStatus.OPEN, "챙기는 중",
        FollowUpStatus.DONE, "끝남",
        FollowUpStatus.DROPPED, "그만둠",
        FollowUpStatus.REJECTED, "거절함"));

    /**
     * 입력에 싣는 반응 낱말이다. 노출과 열기는 반응이 아니라 싣지 않는다. {@code DISMISSED} 는 지금 화면에서 숨긴 것과 받아들인 할 일을
     * 그만둔 것을 함께 가리켜 「내림」 으로 적는다. 어느 쪽인지는 같은 줄의 상태가 보인다.
     */
    private static final Map<FeedbackEventType, String> REACTION_LABELS = new EnumMap<>(Map.of(
        FeedbackEventType.ACCEPTED, "받아들임",
        FeedbackEventType.REJECTED, "거절",
        FeedbackEventType.DISMISSED, "내림",
        FeedbackEventType.POSTPONED, "미룸",
        FeedbackEventType.RESTORED, "되돌림",
        FeedbackEventType.EDITED, "고침",
        FeedbackEventType.EXECUTION_SUCCEEDED, "끝냄"));

    private final CurrentUser owner;
    private final Long agentId;
    private final ProactiveCheck check;
    /** 사건 실행이 살펴볼 새 항목이다. 다른 계기는 null 이다. */
    private final EventBatch event;
    private final ProactiveCheckRun.Deps deps;


    ProactiveCheckInput(
        CurrentUser owner, Long agentId, ProactiveCheck check, EventBatch event, ProactiveCheckRun.Deps deps) {
        this.owner = owner;
        this.agentId = agentId;
        this.check = check;
        this.event = event;
        this.deps = deps;
    }


    String buildInput(Instant now) {
        Long conversationId = check.conversationId();
        StringBuilder text = new StringBuilder(event == null ? OPENING : EVENT_OPENING)
            .append("\n\n지금 시각: ")
            .append(now);
        if (event != null) {
            text.append("\n\n").append(eventSection());
        }
        text.append("\n\n변화 신호\n");
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
        // 앞 살펴보기는 시작할 때 입력을 만들었으므로 그 시작 시각부터 본다. 실패하거나 멈춘 살펴보기는 모델이 결과를 내지 못해 경계를 옮기지 않는다
        Instant since = deps.checks()
            .findFirstByConversationIdAndStatusOrderByIdDesc(conversationId, CheckStatus.SUCCEEDED)
            .map(ProactiveCheck::startedAt)
            .orElse(null);
        text.append("\n\n").append(deps.wakeMemory().section(owner, agentId, since, now));
        text.append("\n\n최근 제안\n").append(recentSuggestions(now));
        text.append("\n\n최근 보고에서 알린 것\n").append(recentReported(now));
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
     * 그 점검 대화의 최근 제안 줄을 한 줄씩 적고 {@code <external-data>} 로 감싼다. 제목이 모델이 쓴 글이기 때문이다. 상태와 반응 낱말은
     * Control Plane 이 정하지만 같은 줄의 제목과 함께 감싼다.
     *
     * <p>거절한 줄은 싣고 받아들이지 않은 채 만료한 줄은 싣지 않는다. 반응은 그 줄에 사용자가 남긴 마지막 반응 사건이다.
     */
    private String recentSuggestions(Instant now) {
        ProactiveCheckProperties limits = deps.properties().current();
        List<RecentSuggestion> recent = deps.suggestions()
            .recent(
                owner.id(), check.conversationId(), now.minus(limits.digestWindow()), now, limits.digestMaxItems());
        if (recent.isEmpty()) {
            return NO_RECENT_SUGGESTIONS;
        }
        Map<String, FeedbackEventType> reactions = deps.reactions()
            .lastUserReactions(
                owner.id(),
                recent.stream().map(row -> FeedbackSubjectType.FOLLOW_UP.key(row.publicId())).toList(),
                REACTION_LABELS.keySet());
        String lines = recent.stream()
            .map(row -> "- " + orDash(row.title())
                + " · " + STATUS_LABELS.get(row.status())
                + " · 반응 " + REACTION_LABELS.getOrDefault(
                    reactions.get(FeedbackSubjectType.FOLLOW_UP.key(row.publicId())), "없음"))
            .collect(Collectors.joining("\n"));
        return ExternalData.wrap(lines);
    }


    /**
     * 같은 점검 대화의 최근 보고가 알린 발견과 받아들인 문제 키를 한 줄씩 적고 {@code <external-data>} 로 감싼다. 제목과 문제 키가 모델이 쓴
     * 글이기 때문이다. 둘을 합쳐 {@code digest-max-items} 줄까지 최근 보고부터 싣는다.
     *
     * <p>메시지 수는 그 보고를 남긴 살펴보기가 끝난 뒤부터 센다. 같은 살펴보기의 발견은 한 번만 센다.
     */
    private String recentReported(Instant now) {
        ProactiveCheckProperties limits = deps.properties().current();
        List<ProactiveCheck> reported = deps.checks()
            .findByConversationIdAndReportIsNotNullAndStartedAtAfterOrderByIdDesc(
                check.conversationId(), now.minus(limits.digestWindow()));
        Map<Long, String> countsByCheck = new HashMap<>();
        String lines = reported.stream()
            .flatMap(previous -> Stream.concat(
                previous.report().announced().stream()
                    .map(finding -> "- " + orDash(finding.topicKey())
                        + " · " + orDash(finding.title())
                        + " · " + orDash(finding.sourceUrl())
                        + " · 알린 날 " + DATE.format(previous.startedAt())
                        + " · 그 뒤 사용자 메시지 "
                        + countsByCheck.computeIfAbsent(previous.id(), id -> userMessagesAfter(previous.finishedAt()))),
                previous.report().acceptedProblemKeys().stream()
                    .map(key -> "- 받아들인 문제 키 · " + key + " · 알린 날 " + DATE.format(previous.startedAt()))))
            .limit(limits.digestMaxItems())
            .collect(Collectors.joining("\n"));
        return lines.isEmpty() ? NO_RECENT_REPORTED : ExternalData.wrap(lines);
    }


    /**
     * 사건 실행의 「새 사건」 절이다. 항목마다 식별자와 원문 링크를 한 줄씩 적고 {@code <external-data>} 로 감싼다. 식별자와 링크는 플러그인이
     * 낸 값이기 때문이다. 제목과 본문은 싣지 않는다. 모델이 도구로 읽는다.
     */
    private String eventSection() {
        String lines = event.ids().stream()
            .map(id -> "- " + id + " · " + event.linkOf(id))
            .collect(Collectors.joining("\n"));
        return "새 사건\n- 원천: " + event.sourceTitle()
            + "\n- 새 " + event.noun() + ": " + event.newCount() + "건, 아래에 " + event.ids().size() + "건을 싣는다\n"
            + ExternalData.wrap(lines);
    }


    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
