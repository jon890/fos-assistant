package com.bifos.assistant.connector.connection.application.dto;

import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration;
import java.util.List;

/** 연결 화면에서 단추를 그릴 때 쓰는 정보다. 앱 자격과 대시보드 내부 선언은 담지 않는다. */
public record ConnectorOAuthSummary(
    String provider, boolean ready, List<String> fields, String settingsUrl, String installSettingsUrl) {

    public static ConnectorOAuthSummary from(ConnectorOAuthDeclaration declaration, boolean ready) {
        if (declaration == null) {
            return null;
        }
        return new ConnectorOAuthSummary(
            declaration.provider(), ready, declaration.fields(), declaration.settingsUrl(), declaration.installSettingsUrl());
    }
}
