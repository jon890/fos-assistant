package com.bifos.assistant.quality.application;

import com.bifos.assistant.mcp.application.McpConnectionLiveness;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 평가 비서 profile 의 Control Plane MCP 연결을 문항의 시작과 끝에서 본다(docs/features/agent-quality/design.md 의 「평가 환경 오류」).
 *
 * <p>배포 뒤 확인과 묶음 평가가 함께 쓴다. 통과하지 못한 문항에서 둘 중 하나라도 거짓이면 그 실패는 환경 오류다. 시작에서도 보는 까닭은 끊긴 채 시작해 도중에
 * 다시 연결된 문항을 놓치지 않으려는 것이다.
 */
@Component
@RequiredArgsConstructor
class ControlPlaneConnection {

    private final McpConnectionLiveness liveness;
    private final TurnDriver driver;
    private final Clock clock;


    /** 문항을 보내기 전의 연결이다. 서버가 막 떠 아직 알 수 없으면 살아 있다고 보고 끝에서 다시 본다. */
    boolean atStart(String profileName) {
        return liveness.connected(profileName) || clock.instant().isBefore(liveness.decidedFrom());
    }


    /** 문항이 끝난 뒤의 연결이다. 서버가 막 떠 아직 알 수 없으면 알 수 있는 때까지 그 profile 의 요청을 기다린다. */
    boolean atEnd(String profileName) {
        return liveness.connected(profileName) || driver.waitUntil(liveness.decidedFrom(), () -> liveness.connected(profileName));
    }
}
