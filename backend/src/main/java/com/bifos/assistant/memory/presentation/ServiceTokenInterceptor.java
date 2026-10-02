package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.ServiceTokenService;
import com.bifos.assistant.memory.application.model.ServicePrincipal;
import com.bifos.assistant.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code /api/v1/service/**} 의 인증이다(ADR-056). 서비스 토큰이 증명한 요청자를 요청 속성에 둔다.
 *
 * <p>Spring Security 필터가 아니라 인터셉터로 둔 것은 {@code shared} 가 {@code memory} 를 쓰지 않게 하기 위해서다.
 * 경로 패턴으로 걸어 두므로 컨트롤러가 검사를 빠뜨려도 열리지 않는다.
 *
 * <p>상태만 적고 본문을 쓰지 않는다. 401 은 토큰이 없는 경우와 틀린 경우와 폐기와 만료와 주인이 꺼진 경우 모두 같다.
 */
@Component
@RequiredArgsConstructor
public class ServiceTokenInterceptor implements HandlerInterceptor {

    /** 어노테이션 값으로 쓰므로 컴파일 시점 상수여야 한다. 클래스 이름을 이어 붙인 식은 쓰지 못한다. */
    public static final String PRINCIPAL_ATTRIBUTE = "com.bifos.assistant.memory.presentation.service-principal";

    private static final String BEARER = "Bearer ";

    private final ServiceTokenService tokens;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 브라우저에서 부르는 길이 아니다
        if (request.getHeader("Origin") != null) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return false;
        }
        String header = request.getHeader("Authorization");
        if (header == null
                || !header.startsWith(BEARER)
                || header.substring(BEARER.length()).isBlank()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        try {
            ServicePrincipal principal =
                    tokens.authenticate(header.substring(BEARER.length()).trim());
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            return true;
        } catch (ApiException e) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
    }
}
