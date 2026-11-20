package com.bifos.assistant.usage.execution.application.port;

import com.bifos.assistant.usage.execution.application.dto.ConnectorToolName;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 도구의 등록 이름을 플러그인의 서비스 이름과 도구 제목으로 바꾼다. 위 패키지인 connector 가 구현한다.
 *
 * <p>대화 스트림, 실행 트리, 메시지 조회의 작업 과정 요약이 같은 이름을 쓴다. 카탈로그를 읽지 못하면 예외 대신 빈 묶음을 돌려준다.
 * 이름이 없으면 화면은 「연결된 서비스」 로 보이고, 작업 과정이 카탈로그 장애로 실패하지 않게 하려는 것이다.
 */
public interface ConnectorToolNames {

    /** 등록 이름마다의 이름이다. 플러그인 도구가 아니거나 어느 플러그인의 것인지 찾지 못한 이름은 빠진다. */
    Map<String, ConnectorToolName> namesOf(Collection<String> hermesTools);


    /** 등록 이름 하나의 이름이다. 찾지 못하면 null 이다. */
    default ConnectorToolName nameOf(String hermesTool) {
        if (hermesTool == null) {
            return null;
        }
        return namesOf(List.of(hermesTool)).get(hermesTool);
    }
}
