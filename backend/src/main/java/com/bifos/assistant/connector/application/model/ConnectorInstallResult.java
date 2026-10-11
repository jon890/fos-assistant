package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;

/** 외부 설치와 반영 확인의 결과다. 선언 밖 도구 수는 probe를 실행한 경우에만 들어 있다. */
public record ConnectorInstallResult(
    InstallResult installed, boolean envRestart, ResyncOutcome outcome, Integer undeclaredTools) {
}
