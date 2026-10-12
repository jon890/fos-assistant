package com.bifos.assistant.hermes.dto;

import java.util.List;
import java.util.Map;

/**
 * 카탈로그가 내는 도구 하나의 정책 선언이다(ADR-049).
 *
 * <p>위험도와 승인 방식을 받은 글자 그대로 담는다. 뜻을 읽고 하한을 검사하는 것은 받는 쪽이 한다.
 *
 * @param risk 위험도 글. 응답에 없으면 null
 * @param approval 승인 방식 글. 응답에 없으면 null
 * @param title 사람에게 보일 이름. 선언하지 않았으면 null
 * @param grant 그 도구에 상시 허락을 줄 수 있는가(ADR-065). 옛 대시보드 plugin 은 이 칸을 내지 않고, 응답에 없으면 null
 * 이다. boolean 이 아닌 값은 거짓으로 읽는다
 * @param description 도구의 기능과 입력, 결과, 부작용을 설명하는 평문. 읽을 수 없으면 null
 * @param identifiers 승인 카드가 길이로 가리지 않을 식별자 인자의 이름(ADR-089). 응답에 없거나 읽을 수 없으면 빈 목록이다
 * @param argLabels 승인 카드 인자의 표시 이름. 정책과 분리하며 누락·불량 메타데이터는 빈 객체다
 * @param outbound 데이터를 계정 밖의 사람에게 보내는 도구인가. 옛 대시보드 plugin 은 이 칸을 내지 않고, 응답에 없으면 null
 * 이다. boolean 이 아닌 값은 참으로 읽는다
 */
public record ConnectorTool(
    String name, String risk, String approval, String title, Boolean grant, List<String> identifiers, String description,
    Map<String, String> argLabels, Boolean outbound) {

    public ConnectorTool {
        description = ConnectorToolDescription.read(description);
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        argLabels = ConnectorArgumentLabels.read(argLabels);
    }


    /** 밖으로 나가는지를 내지 않는 옛 카탈로그다. */
    public ConnectorTool(String name, String risk, String approval, String title, Boolean grant, List<String> identifiers,
        String description, Map<String, String> argLabels) {
        this(name, risk, approval, title, grant, identifiers, description, argLabels, null);
    }


    /** 표시 이름이 없는 옛 카탈로그다. */
    public ConnectorTool(String name, String risk, String approval, String title, Boolean grant, List<String> identifiers,
        String description) {
        this(name, risk, approval, title, grant, identifiers, description, Map.of());
    }


    /** 상세 설명을 선언하지 않은 도구다. */
    public ConnectorTool(String name, String risk, String approval, String title, Boolean grant, List<String> identifiers) {
        this(name, risk, approval, title, grant, identifiers, null);
    }


    /** 식별자 인자를 선언하지 않은 도구다. */
    public ConnectorTool(String name, String risk, String approval, String title, Boolean grant) {
        this(name, risk, approval, title, grant, List.of());
    }
}
