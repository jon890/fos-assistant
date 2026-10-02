package com.bifos.assistant.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.application.AgentProperties;
import com.bifos.assistant.agent.application.StarterProperties;
import com.bifos.assistant.chat.infra.ArtifactProperties;
import com.bifos.assistant.chat.infra.ArtifactSourceProperties;
import com.bifos.assistant.chat.infra.AttachmentProperties;
import com.bifos.assistant.chat.application.DelegationWakeProperties;
import com.bifos.assistant.context.ContextProperties;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.memory.application.MemoryProposalProperties;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.people.application.PeopleProperties;
import com.bifos.assistant.shared.auth.AuthProperties;
import com.bifos.assistant.skill.infra.SkillProperties;
import com.bifos.assistant.usage.infra.PricingProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.validation.annotation.Validated;

/** {@code @Validated} 를 단 설정 record 가 프록시로 감싸지지 않고 그대로 바인딩되는지 단언한다. */
@SpringBootTest
@ActiveProfiles("test")
class ValidatedPropertiesBindingTest {

    private static final List<Class<?>> PROPERTIES_TYPES = List.of(
            AgentProperties.class,
            StarterProperties.class,
            ArtifactProperties.class,
            ArtifactSourceProperties.class,
            AttachmentProperties.class,
            DelegationWakeProperties.class,
            ContextProperties.class,
            HermesProperties.class,
            MemoryProposalProperties.class,
            DelegationProperties.class,
            PeopleProperties.class,
            AuthProperties.class,
            SkillProperties.class,
            PricingProperties.class);

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("설정 record 열넷은 빈으로 꺼낼 수 있고 프록시가 아닌 record 타입 그대로다")
    void beansAreBoundWithoutProxy() {
        for (Class<?> type : PROPERTIES_TYPES) {
            Object bean = context.getBean(type);
            assertThat(bean.getClass()).as("%s 의 빈 클래스", type.getSimpleName()).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("설정 record 열넷은 모두 Validated 를 단다")
    void everyPropertiesTypeIsValidated() {
        List<String> missing = PROPERTIES_TYPES.stream()
                .filter(type -> !AnnotatedElementUtils.hasAnnotation(type, Validated.class))
                .map(Class::getName)
                .toList();
        assertThat(missing).as("Validated 가 없는 설정 타입").isEmpty();
    }
}
