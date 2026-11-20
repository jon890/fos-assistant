package com.bifos.assistant.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bifos.assistant.agent.application.AgentDefaultToolsets;
import com.bifos.assistant.agent.application.AgentSandboxSweep;
import com.bifos.assistant.agent.config.AgentProperties;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** 기동 이벤트가 설정에 따라 다시 저장 작업을 백그라운드로 띄우는지 본다(ADR-20261120 / no-local-shell). */
class AgentSandboxSweepStartTest {

    private final BackgroundTasks backgroundTasks = mock(BackgroundTasks.class);


    @Test
    @DisplayName("설정이 켜져 있으면 기동 이벤트에서 다시 저장 작업을 백그라운드로 띄운다")
    void startsInBackgroundWhenEnabled() {
        sweep(true).onReady();

        verify(backgroundTasks).start(eq("agent-sandbox-sweep"), any(Runnable.class));
    }


    @Test
    @DisplayName("설정이 꺼져 있으면 기동 이벤트에서 아무것도 띄우지 않는다")
    void doesNothingWhenDisabled() {
        sweep(false).onReady();

        verify(backgroundTasks, never()).start(any(), any());
    }


    private AgentSandboxSweep sweep(boolean enabled) {
        return new AgentSandboxSweep(
            mock(AgentRepository.class),
            mock(AgentDefaultToolsets.class),
            new AgentProperties(null, null, enabled),
            backgroundTasks,
            mock(TransactionTemplate.class));
    }
}
