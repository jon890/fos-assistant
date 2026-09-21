package com.bifos.assistant.shared.config;

import com.bifos.assistant.shared.auth.ControlPlaneJwtFilter;
import com.bifos.assistant.mcp.infra.AgentTokenAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<ControlPlaneJwtFilter> disableDirectJwtFilterRegistration(
            ControlPlaneJwtFilter jwtFilter) {
        FilterRegistrationBean<ControlPlaneJwtFilter> registration =
                new FilterRegistrationBean<>(jwtFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AgentTokenAuthenticationFilter> disableDirectAgentTokenFilterRegistration(
            AgentTokenAuthenticationFilter agentTokenFilter) {
        FilterRegistrationBean<AgentTokenAuthenticationFilter> registration =
                new FilterRegistrationBean<>(agentTokenFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ControlPlaneJwtFilter jwtFilter, AgentTokenAuthenticationFilter agentTokenFilter)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(DispatcherType.ASYNC)
                                        .permitAll()
                                        .requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/api/v1/me")
                                        .permitAll()
                                        // 로그인 판정은 아직 아무 사용자도 없는 시점에 돈다.
                                        // 그 경로가 받는 토큰은 따로라 SignInController 가 직접 검사한다.
                                        .requestMatchers("/api/v1/signin/allowed")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(agentTokenFilter, ControlPlaneJwtFilter.class)
                .build();
    }
}
