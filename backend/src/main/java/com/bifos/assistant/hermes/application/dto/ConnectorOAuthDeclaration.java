package com.bifos.assistant.hermes.application.dto;

import java.util.List;

/** 대시보드가 카탈로그에 실은 단추 연결 선언이다. 제공자 호출 규칙은 대시보드가 갖는다. */
public record ConnectorOAuthDeclaration(
    String provider,
    AppScope appScope,
    List<String> fields,
    String settingsUrl,
    String installSettingsUrl) {

    public ConnectorOAuthDeclaration {
        fields = List.copyOf(fields);
    }


    /** 플러그인마다 앱이 하나인지, 같은 제공자가 하나를 공유하는지 나타낸다. */
    public enum AppScope {
        PLUGIN,
        SHARED
    }
}
