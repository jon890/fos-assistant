package com.bifos.assistant.connector.connection.application.dto;

import com.bifos.assistant.hermes.application.port.HermesConnectorClient.InstallResult;

/** 외부 설치와 반영 확인의 결과다. 선언 밖 도구 수는 probe를 실행한 경우에만 들어 있다. */
public record ConnectorInstallResult(
    InstallResult installed, ResyncOutcome outcome, Integer undeclaredTools) {
}
