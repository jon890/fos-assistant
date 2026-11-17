package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.nextturn.application.port.DelegationOutputClip;
import com.bifos.assistant.chat.nextturn.config.DelegationWakeProperties;
import com.bifos.assistant.orchestration.application.SubagentStopRecorder;
import com.bifos.assistant.orchestration.application.dto.SubagentStopResult;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.orchestration.infra.SubagentResultTexts;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 끝 소식의 상태 해석, 설정에 따른 전달 표시, 깨우기 예약을 본다(ADR-20261117 / native-delegation-wake). */
class SubagentStopRecorderTest {
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final String PROFILE = "stop-recorder";
    private static final String ROOT = "fos-root";
    private static final String CHILD = "child-1";

    private final HermesSessionBindingRepository bindings = mock(HermesSessionBindingRepository.class);
    private final AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
    private final SubagentResultTexts texts = mock(SubagentResultTexts.class);
    private final DelegationOutputClip outputs = mock(DelegationOutputClip.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final BackgroundTasks backgroundTasks = mock(BackgroundTasks.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);


    @Test
    @DisplayName("completed 는 SUCCEEDED 이고 결과 글을 잘라 적은 뒤 6초 뒤 깨우기를 한 번 예약한다")
    void completedIsSucceededClipsAndSchedulesWakeOnce() {
        HermesSessionBinding binding = binding(true);
        given(binding, 1);
        when(outputs.clip("긴 결과")).thenReturn("자른 결과");

        SubagentStopResult result = recorder(true).record(PROFILE, ROOT, ROOT, CHILD, "completed", "긴 결과");

        assertThat(result).isEqualTo(SubagentStopResult.RECORDED);
        verify(bindings).recordStop(eq(10L), eq(NOW), eq("SUCCEEDED"), eq("자른 결과"), isNull(), isNull(), isNull());
        verify(scheduler).schedule(any(Runnable.class), eq(NOW.plusSeconds(6)));
    }


    @Test
    @DisplayName("interrupted 와 모르는 상태는 FAILED 다")
    void interruptedAndUnknownStatusAreFailed() {
        given(binding(true), 1);
        when(outputs.clip(anyString())).thenAnswer(inv -> inv.getArgument(0));

        recorder(true).record(PROFILE, ROOT, ROOT, CHILD, "interrupted", "글");
        recorder(true).record(PROFILE, ROOT, ROOT, CHILD, "weird", null);

        verify(bindings).recordStop(eq(10L), eq(NOW), eq("FAILED"), eq("글"), isNull(), isNull(), isNull());
        verify(bindings).recordStop(eq(10L), eq(NOW), eq("FAILED"), isNull(), isNull(), isNull(), isNull());
    }


    @Test
    @DisplayName("설정이 꺼져 있으면 전달 시각을 함께 적고 깨우기를 예약하지 않는다")
    void disabledSettingMarksDeliveredAndDoesNotSchedule() {
        given(binding(true), 1);

        SubagentStopResult result = recorder(false).record(PROFILE, ROOT, ROOT, CHILD, "completed", null);

        assertThat(result).isEqualTo(SubagentStopResult.RECORDED);
        verify(bindings).recordStop(eq(10L), eq(NOW), eq("SUCCEEDED"), isNull(), isNull(), isNull(), eq(NOW));
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }


    @Test
    @DisplayName("이미 적은 소식이라 바뀐 줄이 없거나 깨울 자식이 아니면 ignored 이고 예약하지 않는다")
    void unchangedOrNonEligibleIsIgnoredWithoutSchedule() {
        given(binding(true), 0);
        assertThat(recorder(true).record(PROFILE, ROOT, ROOT, CHILD, "completed", null)).isEqualTo(SubagentStopResult.IGNORED);

        given(binding(false), 1);
        assertThat(recorder(true).record(PROFILE, ROOT, ROOT, CHILD, "completed", null)).isEqualTo(SubagentStopResult.IGNORED);

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }


    @Test
    @DisplayName("등록 줄이 없으면 NOT_FOUND 다")
    void missingBindingIsNotFound() {
        when(bindings.findByProfileNameAndSessionId(PROFILE, CHILD)).thenReturn(Optional.empty());

        assertThat(recorder(true).record(PROFILE, ROOT, ROOT, CHILD, "completed", null)).isEqualTo(SubagentStopResult.NOT_FOUND);
    }


    @SuppressWarnings("unchecked")
    private void given(HermesSessionBinding binding, int changed) {
        doReturn(Optional.of(binding)).when(bindings).findByProfileNameAndSessionId(PROFILE, CHILD);
        doReturn(changed).when(bindings).recordStop(anyLong(), any(), anyString(), any(), any(), any(), any());
        doAnswer(inv -> ((TransactionCallback<Integer>)inv.getArgument(0)).doInTransaction(null)).when(transactions).execute(any());
        AgentExecution origin = origin();
        doReturn(Optional.of(origin)).when(executions).findById(origin.id());
    }


    private SubagentStopRecorder recorder(boolean wakeEnabled) {
        return new SubagentStopRecorder(
            bindings,
            executions,
            texts,
            outputs,
            LiveProperties.fixed(DelegationWakeProperties.class, new DelegationWakeProperties(wakeEnabled, 10)),
            transactions,
            scheduler,
            backgroundTasks,
            events,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }


    private static HermesSessionBinding binding(boolean wakeEligible) {
        HermesSessionBinding binding = HermesSessionBinding.of(PROFILE, CHILD, origin(), ROOT, ROOT, wakeEligible);
        ReflectionTestUtils.setField(binding, "id", 10L);
        return binding;
    }


    private static AgentExecution origin() {
        AgentExecution origin = AgentExecution.builder()
            .userId(7L)
            .conversationId(30L)
            .profileName(PROFILE)
            .hermesSessionId(ROOT)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(NOW)
            .build();
        ReflectionTestUtils.setField(origin, "id", 20L);
        return origin;
    }
}
