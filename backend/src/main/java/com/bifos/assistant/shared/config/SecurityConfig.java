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
                .exceptionHandling(
                        exceptions ->
                                exceptions.authenticationEntryPoint(
                                        (request, response, exception) ->
                                                response.sendError(401)))
                .authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(DispatcherType.ASYNC)
                                        .permitAll()
                                        .requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/api/v1/me")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(agentTokenFilter, ControlPlaneJwtFilter.class)
                .build();
    }
}
