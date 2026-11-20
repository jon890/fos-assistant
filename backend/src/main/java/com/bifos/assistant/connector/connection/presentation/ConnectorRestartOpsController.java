package com.bifos.assistant.connector.connection.presentation;

import com.bifos.assistant.connector.connection.application.ConnectorRestartOps;
import com.bifos.assistant.connector.connection.presentation.ConnectionDtos.RestartResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 정기 작업이 부르는 재시작 조회다(ADR-20261120 / connector-auto-apply). 사용자 JWT 가 아니라 서비스 Bearer 토큰 하나로 인증한다.
 * 경로는 보안 설정에서 열려 있고 이 controller 가 직접 인증한다.
 */
@RestController
@RequiredArgsConstructor
public class ConnectorRestartOpsController {
    private final ConnectorRestartOps ops;


    @GetMapping(value = "/internal/ops/connector-restart", produces = MediaType.APPLICATION_JSON_VALUE)
    public RestartResponse restart(HttpServletRequest request) {
        ops.authenticate(request.getHeader("Authorization"));
        return RestartResponse.from(ops.snapshot());
    }
}
