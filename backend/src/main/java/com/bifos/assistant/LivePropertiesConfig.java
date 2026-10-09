package com.bifos.assistant;

import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.chat.application.DelegationWakeProperties;
import com.bifos.assistant.chat.application.ModelTierProperties;
import com.bifos.assistant.chat.application.StarterProperties;
import com.bifos.assistant.connector.application.ConnectorPolicyProperties;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.memory.application.MemoryEncryptionProperties;
import com.bifos.assistant.memory.application.MemoryProposalProperties;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.proactive.application.AutonomyProperties;
import com.bifos.assistant.proactive.application.ProactiveCheckProperties;
import com.bifos.assistant.proactive.application.ProactiveLoopProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.usage.application.UserExecutionProperties;
import com.bifos.assistant.usage.infra.PricingProperties;
import com.bifos.assistant.workspace.infra.WorkspaceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 실행 중에 쓰는 설정 묶음마다 {@link LiveProperties} 빈을 하나씩 만든다(ADR-20261007 / live-properties).
 *
 * <p>운영 빈은 기동 때 바인딩한 record 를 그대로 돌려준다. 빈 이름은 {@code <record 이름 lowerCamel>Live} 다.
 * 이 클래스만 위 설정 record 를 주입받는다. 구조 규칙이 확인한다.
 *
 * <p>여러 최상위 패키지의 설정을 함께 읽으므로 {@code shared} 가 아니라 패키지 루트에 둔다.
 * {@code shared} 는 다른 최상위 패키지에 의존하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class LivePropertiesConfig {

    @Bean
    public LiveProperties<DelegationWakeProperties> delegationWakePropertiesLive(DelegationWakeProperties value) {
        return LiveProperties.fixed(DelegationWakeProperties.class, value);
    }

    @Bean
    public LiveProperties<UserExecutionProperties> userExecutionPropertiesLive(UserExecutionProperties value) {
        return LiveProperties.fixed(UserExecutionProperties.class, value);
    }

    @Bean
    public LiveProperties<ProactiveCheckProperties> proactiveCheckPropertiesLive(ProactiveCheckProperties value) {
        return LiveProperties.fixed(ProactiveCheckProperties.class, value);
    }

    @Bean
    public LiveProperties<StarterProperties> starterPropertiesLive(StarterProperties value) {
        return LiveProperties.fixed(StarterProperties.class, value);
    }

    @Bean
    public LiveProperties<DelegationProperties> delegationPropertiesLive(DelegationProperties value) {
        return LiveProperties.fixed(DelegationProperties.class, value);
    }

    @Bean
    public LiveProperties<AutonomyProperties> autonomyPropertiesLive(AutonomyProperties value) {
        return LiveProperties.fixed(AutonomyProperties.class, value);
    }

    @Bean
    public LiveProperties<ProactiveLoopProperties> proactiveLoopPropertiesLive(ProactiveLoopProperties value) {
        return LiveProperties.fixed(ProactiveLoopProperties.class, value);
    }

    @Bean
    public LiveProperties<PricingProperties> pricingPropertiesLive(PricingProperties value) {
        return LiveProperties.fixed(PricingProperties.class, value);
    }

    @Bean
    public LiveProperties<MemoryEncryptionProperties> memoryEncryptionPropertiesLive(MemoryEncryptionProperties value) {
        return LiveProperties.fixed(MemoryEncryptionProperties.class, value);
    }

    @Bean
    public LiveProperties<ModelTierProperties> modelTierPropertiesLive(ModelTierProperties value) {
        return LiveProperties.fixed(ModelTierProperties.class, value);
    }

    @Bean
    public LiveProperties<ConnectorPolicyProperties> connectorPolicyPropertiesLive(ConnectorPolicyProperties value) {
        return LiveProperties.fixed(ConnectorPolicyProperties.class, value);
    }

    @Bean
    public LiveProperties<MemoryProposalProperties> memoryProposalPropertiesLive(MemoryProposalProperties value) {
        return LiveProperties.fixed(MemoryProposalProperties.class, value);
    }

    @Bean
    public LiveProperties<BrowserProperties> browserPropertiesLive(BrowserProperties value) {
        return LiveProperties.fixed(BrowserProperties.class, value);
    }

    @Bean
    public LiveProperties<WorkspaceProperties> workspacePropertiesLive(WorkspaceProperties value) {
        return LiveProperties.fixed(WorkspaceProperties.class, value);
    }

    /** {@code runTimeout()} 과 {@code pollInterval()} 을 읽는 곳만 쓴다. 나머지 칸은 {@link HermesProperties} 를 그대로 주입받는다. */
    @Bean
    public LiveProperties<HermesProperties> hermesPropertiesLive(HermesProperties value) {
        return LiveProperties.fixed(HermesProperties.class, value);
    }
}
