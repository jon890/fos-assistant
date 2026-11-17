package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.chat.nextturn.application.dto.DelegationFinished;
import com.bifos.assistant.chat.nextturn.application.port.DelegationOutputClip;
import com.bifos.assistant.chat.nextturn.config.DelegationWakeProperties;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.orchestration.application.dto.SubagentStopResult;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.domain.type.NativeResultStatus;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.orchestration.infra.SubagentResultTexts;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hermes 내장 위임 하위 에이전트의 끝 소식을 등록 줄에 적고 부모 대화를 깨울 사건을 예약한다(ADR-20261117 / native-delegation-wake).
 *
 * <p>Hermes 는 한 완료 단위의 자식이 모두 끝난 뒤 자식마다 {@code subagent_stop} hook 을 잇달아 부른다. 그래서 바로 깨우지 않고
 * {@link #WAKE_DELAY} 뒤에 사건을 낸다. 그 사이 같은 대화의 끝난 결과가 모여 자동 turn 하나에 든다. 끝난 지 5초가 안 된 결과는 결과를
 * 내는 쪽({@code NativeDelegationResultSource})이 빼므로 다른 계기가 먼저 깨워도 한 단위가 나뉘지 않는다.
 *
 * <p>저장은 아직 소식이 없는 깨울 자식의 줄만 바꾸는 조건부 update 하나다. 설정({@code assistant.delegation-wake.enabled})이 꺼져
 * 있으면 결과를 적되 전달 시각도 함께 적어, 켠 뒤에도 그 결과로 깨우지 않는다. 예약이 실패해도 결과는 남아 다음 turn 닫기와 기동 훑기가
 * 다시 깨운다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubagentStopRecorder {

    /** 끝 소식 뒤 깨우기까지의 시간이다. 한 단위의 hook 이 모두 닿을 5초에 1초를 더했다. */
    static final Duration WAKE_DELAY = Duration.ofSeconds(6);

    private final HermesSessionBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final SubagentResultTexts texts;
    private final DelegationOutputClip outputs;
    private final LiveProperties<DelegationWakeProperties> wakeProperties;
    private final TransactionTemplate transactions;
    private final TaskScheduler scheduler;
    private final BackgroundTasks backgroundTasks;
    private final ApplicationEventPublisher events;
    private final Clock clock;


    /**
     * @param profileName 끝 소식 요청의 토큰이 증명한 profile
     * @param rootSessionId 서명을 확인한 루트 session
     * @param parentSessionId 서명을 확인한 부모 session. 등록 뒤 압축으로 바뀔 수 있어 달라도 받는다
     * @param childSessionId 끝난 하위 에이전트 session
     * @param childStatus hook 이 받은 상태. {@code completed} 가 아니면 실패다
     * @param childSummary hook 이 받은 결과 글. 위임 답과 같은 상한으로 자른다
     * @throws ApiException {@link ErrorCode#SESSION_BINDING_REJECTED}. 서명한 루트가 등록 줄의 루트와 다를 때
     */
    public SubagentStopResult record(
        String profileName,
        String rootSessionId,
        String parentSessionId,
        String childSessionId,
        String childStatus,
        String childSummary) {
        Optional<HermesSessionBinding> found = bindings.findByProfileNameAndSessionId(profileName, childSessionId);
        if (found.isEmpty()) {
            log.info("등록이 없는 하위 에이전트의 끝 소식이다 profile={}", profileName);
            return SubagentStopResult.NOT_FOUND;
        }
        HermesSessionBinding binding = found.get();
        if (!binding.rootSessionId().equals(rootSessionId)) {
            log.warn("하위 에이전트 끝 소식을 거절했다 profile={} reason={}", profileName, "서명한 루트가 등록 줄의 루트와 다르다");
            throw new ApiException(ErrorCode.SESSION_BINDING_REJECTED, "session binding is rejected");
        }
        if (!binding.parentSessionId().equals(parentSessionId)) {
            log.info("하위 에이전트 끝 소식의 부모가 등록과 다르다 profile={} bindingId={}", profileName, binding.id());
        }
        if (!binding.wakeEligible()) {
            return SubagentStopResult.IGNORED;
        }
        boolean wakeEnabled = wakeProperties.current().enabled();
        Instant now = clock.instant();
        Integer changed = transactions.execute(status -> save(binding, childStatus, childSummary, wakeEnabled, now));
        if (changed == null || changed == 0) {
            return SubagentStopResult.IGNORED;
        }
        log.info(
            "하위 에이전트 끝 소식을 적었다 profile={} bindingId={} originExecutionId={} wake={}",
            profileName,
            binding.id(),
            binding.originExecutionId(),
            wakeEnabled);
        if (wakeEnabled) {
            executions.findById(binding.originExecutionId())
                .map(AgentExecution::conversationId)
                .ifPresent(conversationId -> scheduleWake(conversationId, now));
        }
        return SubagentStopResult.RECORDED;
    }


    /** 결과 글을 자르고 봉해 적는다. 봉한 key 가 맡긴 일의 key 와 다르면 맡긴 일도 새 key 로 다시 봉한다. 두 칸이 key 칸 하나를 쓴다. */
    private int save(
        HermesSessionBinding binding, String childStatus, String childSummary, boolean wakeEnabled, Instant now) {
        String clipped = childSummary == null ? null : outputs.clip(childSummary);
        Optional<SealedText> sealedText = clipped == null
            ? Optional.empty()
            : texts.seal(binding.id(), binding.userId(), SubagentResultTexts.RESULT_TEXT, clipped);
        // 결과 글을 봉하지 못했으면(암호화가 꺼졌다) 평문이라 key 를 비운다. 맡긴 일도 같은 key 로 맞춰 다시 적는다.
        Long keyId = clipped == null ? binding.resultKeyId() : sealedText.map(SealedText::keyId).orElse(null);
        String goal = binding.goalPreview();
        if (goal != null && !Objects.equals(keyId, binding.resultKeyId())) {
            goal = resealGoal(binding, keyId);
        }
        return bindings.recordStop(
            binding.id(),
            now,
            NativeResultStatus.fromChildStatus(childStatus).name(),
            sealedText.map(SealedText::content).orElse(clipped),
            keyId,
            goal,
            wakeEnabled ? null : now);
    }


    /**
     * 맡긴 일을 옛 key 로 열어 새 key 로 다시 봉한다. 새 key 가 비었으면 평문으로 적는다. 열지 못하거나 새로 봉하지 못하면 맡긴 일을 비운다.
     * 알림 줄은 맡긴 일 없이 남는다.
     */
    private String resealGoal(HermesSessionBinding binding, Long keyId) {
        Optional<String> plain = texts.open(
            binding.id(), binding.userId(), SubagentResultTexts.GOAL_PREVIEW, binding.resultKeyId(), binding.goalPreview());
        if (plain.isEmpty() || keyId == null) {
            return plain.orElse(null);
        }
        return texts.seal(binding.id(), binding.userId(), SubagentResultTexts.GOAL_PREVIEW, plain.get())
            .filter(sealed -> Objects.equals(sealed.keyId(), keyId))
            .map(SealedText::content)
            .orElse(null);
    }


    /** {@link #WAKE_DELAY} 뒤 새 가상 스레드에서 사건을 낸다. 스케줄러 스레드에서 DB 를 읽지 않는다. 실패는 경고 로그만 남긴다. */
    private void scheduleWake(Long conversationId, Instant now) {
        try {
            scheduler.schedule(() -> startWake(conversationId), now.plus(WAKE_DELAY));
        } catch (RuntimeException ex) {
            log.warn("내장 위임 결과의 깨우기를 예약하지 못했다 conversationId={}", conversationId, ex);
        }
    }


    private void startWake(Long conversationId) {
        try {
            backgroundTasks.start(
                "native-delegation-wake-" + conversationId,
                () -> events.publishEvent(new DelegationFinished(conversationId, null)));
        } catch (RuntimeException | Error ex) {
            log.warn("내장 위임 결과의 깨우기 스레드를 띄우지 못했다 conversationId={}", conversationId, ex);
        }
    }
}
