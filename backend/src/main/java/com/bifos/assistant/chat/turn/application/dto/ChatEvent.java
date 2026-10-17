package com.bifos.assistant.chat.turn.application.dto;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.execution.domain.InternalValuePolicy;
import com.bifos.assistant.usage.execution.domain.ToolDetailPolicy;
import java.util.Optional;
import java.util.UUID;

/**
 * 대화 한 turn 이 도는 동안 화면으로 보내는 사건이다.
 *
 * @param conversationId 대화의 공개 식별자. 대화 번호는 화면에 내보내지 않는다
 * @param phase 도구나 하위 에이전트 사건의 시작 또는 완료 단계
 * @param durationMs 완료 사건의 걸린 시간
 * @param failed 완료 사건의 실패 여부
 * @param subagentId 하위 에이전트 사건의 짝을 맞추는 번호
 * @param goal 하위 에이전트의 목표. 없으면 Hermes 의 설명
 * @param model 하위 에이전트의 모델
 * @param inputTokens 하위 에이전트의 입력 토큰
 * @param outputTokens 하위 에이전트의 출력 토큰
 * @param alreadyStreamed Hermes가 중간 안내의 일부를 delta로 전달했다고 알렸는가
 * @param streamedPrefixLength 파서가 안내로 확인한 직전 delta 구간의 UTF-16 길이
 * @param interimId 실행 안에서 중간 안내의 재수신을 가려낼 식별자
 */
public record ChatEvent(
    String type,
    String text,
    String toolName,
    String detail,
    UUID conversationId,
    Long messageId,
    Long executionId,
    String code,
    String message,
    String phase,
    Long durationMs,
    Boolean failed,
    String subagentId,
    String goal,
    String model,
    Long inputTokens,
    Long outputTokens,
    Boolean alreadyStreamed,
    Integer streamedPrefixLength,
    String interimId) {

    public ChatEvent(String type, String text, String toolName, String detail, UUID conversationId,
        Long messageId, Long executionId, String code, String message,
        String phase, Long durationMs, Boolean failed, String subagentId, String goal, String model,
        Long inputTokens, Long outputTokens) {
        this(type, text, toolName, detail, conversationId, messageId, executionId, code, message, phase, durationMs, failed, subagentId, goal, model, inputTokens, outputTokens, null, null, null);
    }


    /** 저장하지 않는 중간 안내와 확인된 직전 delta 구간의 UTF-16 길이다. */
    public static ChatEvent interim(String text, Boolean alreadyStreamed, int streamedPrefixLength, String interimId) {
        return new ChatEvent("interim", text, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, alreadyStreamed, streamedPrefixLength, interimId);
    }


    public static final String STARTED = "started";
    public static final String COMPLETED = "completed";


    public static ChatEvent delta(String text) {
        return new ChatEvent(
            "delta", text, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null);
    }


    /** 이 turn 의 루트 실행 줄이 만들어졌다. */
    public static ChatEvent started(UUID conversationId, Long executionId) {
        return new ChatEvent(
            "started",
            null,
            null,
            null,
            conversationId,
            null,
            executionId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }


    public static ChatEvent tool(String toolName, String detail, String phase, Long durationMs, Boolean failed) {
        return new ChatEvent(
            "tool",
            null,
            toolName,
            detail,
            null,
            null,
            null,
            null,
            null,
            phase,
            durationMs,
            failed,
            null,
            null,
            null,
            null,
            null);
    }


    /**
     * 이 사건을 보는 사람에게 맞춰 돌려준다.
     *
     * <p>{@code tool} 사건의 {@code detail} 은 {@link ToolDetailPolicy} 가 허락할 때만 싣는다. 허락하지
     * 않으면 {@code detail} 만 비운 새 사건을 돌려준다. 근거는 ADR-038 에 있다.
     *
     * <p>{@link InternalValuePolicy} 가 허락하지 않는 사람에게는 {@code subagent} 사건의 모델과 토큰을 비우고,
     * {@code switched} 사건은 보내지 않는다. 보내지 않는 사건은 빈 값으로 돌려준다. 근거는 ADR-063 에 있다.
     *
     * <p>그 밖에는 이 사건을 그대로 돌려준다.
     */
    public Optional<ChatEvent> forViewer(CurrentUser viewer) {
        if ("tool".equals(type) && !ToolDetailPolicy.visibleTo(viewer, toolName)) {
            return Optional.of(redacted(null, model, inputTokens, outputTokens));
        }
        if (InternalValuePolicy.visibleTo(viewer)) {
            return Optional.of(this);
        }
        if ("switched".equals(type)) {
            return Optional.empty();
        }
        if ("subagent".equals(type)) {
            return Optional.of(redacted(detail, null, null, null));
        }
        return Optional.of(this);
    }


    /** 보는 사람에 따라 빼는 칸만 바꾼 새 사건이다. 나머지 칸은 그대로 옮긴다. */
    private ChatEvent redacted(String detail, String model, Long inputTokens, Long outputTokens) {
        return new ChatEvent(
            type,
            text,
            toolName,
            detail,
            conversationId,
            messageId,
            executionId,
            code,
            message,
            phase,
            durationMs,
            failed,
            subagentId,
            goal,
            model,
            inputTokens,
            outputTokens,
            alreadyStreamed,
            streamedPrefixLength,
            interimId);
    }


    public static ChatEvent subagent(
        String subagentId,
        String goal,
        String model,
        String phase,
        Long inputTokens,
        Long outputTokens,
        Long durationMs,
        Boolean failed) {
        return new ChatEvent(
            "subagent",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            phase,
            durationMs,
            failed,
            subagentId,
            goal,
            model,
            inputTokens,
            outputTokens);
    }


    /**
     * 앞 provider 가 막혀 다음 모델로 넘어갔다.
     *
     * <p>{@code text} 에 넘어간 곳의 provider 와 모델을 적는다. 막힌 쪽의 오류 글은 싣지 않는다.
     */
    public static ChatEvent switched(String label) {
        return new ChatEvent(
            "switched",
            label,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }


    /**
     * 지금까지 흘린 답 조각을 화면에서 지우라고 알린다.
     *
     * <p>실패한 시도의 조각이 화면에 남으면 읽는 사람이 그것을 답으로 읽는다.
     */
    public static ChatEvent reset() {
        return new ChatEvent(
            "reset", null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null);
    }


    public static ChatEvent done(UUID conversationId, Long messageId, Long executionId) {
        return new ChatEvent(
            "done",
            null,
            null,
            null,
            conversationId,
            messageId,
            executionId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }


    public static ChatEvent stopped(UUID conversationId, Long messageId, Long executionId) {
        return new ChatEvent(
            "stopped",
            null,
            null,
            null,
            conversationId,
            messageId,
            executionId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }


    /**
     * 대화에 알림 줄이 저장됐다. 위임 결과가 도착했거나 자동 turn 한도에 닿았을 때 낸다.
     *
     * @param messageId 저장된 {@code SYSTEM} 메시지의 번호
     * @param content 알림 줄의 글
     */
    public static ChatEvent system(UUID conversationId, Long messageId, String content) {
        return new ChatEvent(
            "system",
            content,
            null,
            null,
            conversationId,
            messageId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }


    /**
     * 그 대화의 승인 줄이 생겼거나 상태가 바뀌었다(ADR-050). 화면은 이 사건을 받으면 승인 줄을 다시 읽는다.
     *
     * <p>승인 줄의 내용은 싣지 않는다. 승인 카드는 주인만 읽는 조회 응답으로만 그린다.
     *
     * @param actionId 승인 요청 번호. {@code detail} 칸에 글로 싣는다
     */
    public static ChatEvent approval(UUID conversationId, UUID actionId) {
        return new ChatEvent(
            "approval",
            null,
            null,
            actionId.toString(),
            conversationId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }


    /**
     * 대기 메시지를 합쳐 사용자 메시지로 저장했다. 요청한 연결이 없는 turn 이라 화면이 이 사건으로 그 글을 그린다.
     *
     * @param messageId 저장된 {@code USER} 메시지의 번호
     * @param content 합친 글
     */
    public static ChatEvent user(UUID conversationId, Long messageId, String content) {
        return new ChatEvent(
            "user",
            content,
            null,
            null,
            conversationId,
            messageId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }


    /** 대기 줄이 바뀌었다. 화면이 대기 줄을 다시 읽는다. */
    public static ChatEvent pending(UUID conversationId) {
        return new ChatEvent(
            "pending",
            null,
            null,
            null,
            conversationId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }


    public static ChatEvent error(String code, String message) {
        return new ChatEvent(
            "error", null, null, null, null, null, null, code, message, null, null, null, null, null,
            null, null, null);
    }
}
