package com.bifos.assistant.memory.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 서비스 토큰을 쓰는 경로에 인증 인터셉터를 건다.
 *
 * <p>이 경로는 {@code SecurityConfig} 가 {@code permitAll} 로 열어 둔다. 인증은 이 인터셉터가 한다. 경로를 더하면 두
 * 곳을 함께 본다.
 */
@Configuration
@RequiredArgsConstructor
public class ServiceApiConfig implements WebMvcConfigurer {

    private final ServiceTokenInterceptor interceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/service/**");
    }
}
