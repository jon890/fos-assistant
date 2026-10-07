package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.mcp.presentation.AgentTokenAuthenticationFilter;
import com.bifos.assistant.shared.auth.AgentTokenFilter;
import com.bifos.assistant.shared.auth.ControlPlaneJwtFilter;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.SecurityFilterChain;

/**
 * profile 토큰 필터가 {@code shared.auth} 의 타입으로 주입돼도 보안 필터 순서와 서블릿 필터 등록 끄기가 그대로인지 본다.
 */
@BackendIntegrationTest
class AgentTokenFilterWiringTest {

    @Autowired
    private SecurityFilterChain filterChain;

    @Autowired
    private AgentTokenFilter agentTokenFilter;

    @Autowired
    private FilterRegistrationBean<AgentTokenFilter> agentTokenFilterRegistration;

    @Test
    @DisplayName("profile 토큰 필터는 사용자 JWT 필터보다 앞에 한 번만 등록된다")
    void placesAgentTokenFilterOnceBeforeJwtFilter() {
        List<Class<?>> filterClasses = filterChain.getFilters().stream()
                .<Class<?>>map(Filter::getClass)
                .toList();

        assertThat(filterClasses)
                .filteredOn(type -> type == AgentTokenAuthenticationFilter.class || type == ControlPlaneJwtFilter.class)
                .containsExactly(AgentTokenAuthenticationFilter.class, ControlPlaneJwtFilter.class);
    }

    @Test
    @DisplayName("AgentTokenFilter 타입으로 주입받은 빈의 실제 클래스는 AgentTokenAuthenticationFilter 다")
    void injectsAgentTokenAuthenticationFilterAsAgentTokenFilter() {
        assertThat(agentTokenFilter.getClass()).isEqualTo(AgentTokenAuthenticationFilter.class);
    }

    @Test
    @DisplayName("서블릿 필터 등록은 같은 필터 객체를 감싸고 꺼져 있어 필터가 두 번 돌지 않는다")
    void disablesServletRegistrationOfSameFilterInstance() {
        assertThat(agentTokenFilterRegistration.getFilter()).isSameAs(agentTokenFilter);
        assertThat(agentTokenFilterRegistration.isEnabled()).isFalse();
    }
}
