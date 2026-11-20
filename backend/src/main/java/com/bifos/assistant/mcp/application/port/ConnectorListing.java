package com.bifos.assistant.mcp.application.port;

import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.List;
import java.util.Map;

/**
 * 에이전트가 외부 서비스 연결을 안내하려고 플러그인 목록을 읽는 길이다. 구현은 커넥터가 갖고 mcp 는 커넥터를 import 하지 않는다.
 *
 * <p>계약은 {@code docs/features/service-concepts/design.md} 의 「{@code connector_list}」 가 갖는다.
 */
public interface ConnectorListing {

    /**
     * 요청자가 연결할 수 있는 플러그인마다 {@code id}, {@code title}, {@code connected}, {@code thisAgent}({@code none}, {@code ready},
     * {@code pending}), {@code link} 를 담은 항목이다.
     *
     * @param agentId 도구를 부른 실행의 에이전트 번호. 붙은 상태와 링크의 {@code ?agent=} 를 정한다. 없으면 null 이다
     */
    List<Map<String, Object>> list(CurrentUser user, Long agentId);
}
