package com.bifos.assistant.mcp.application.port;

import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.List;
import java.util.Map;

/**
 * 「내 브라우저」 탭을 닫거나 여는 승인 카드를 띄운다(docs/features/agent-browser/design.md 의 「PR1 도구」). 승인 줄은 {@code connector} 가
 * 갖고 {@code mcp} 가 그보다 아래라 이 인터페이스를 두고 {@code connector} 가 구현한다.
 *
 * <p>돌려주는 표의 {@code status} 가 {@link #REQUESTED} 면 카드를 띄운 것이고, 아니면 카드 없이 끝난 까닭이다.
 */
public interface BrowserTabRequests {
    /** 카드를 띄웠다. 아직 아무것도 바뀌지 않았다. */
    String REQUESTED = "approval_requested";
    /** 같은 실행의 같은 요청 카드가 이미 끝났다. */
    String CLOSED = "closed";
    /** 주소의 일부가 카드에서 가려져 사람이 다 볼 수 없다. */
    String NOT_DISPLAYABLE = "url_not_displayable";


    /** 그 탭들을 닫는 카드를 띄운다. 탭은 방금 읽은 목록의 것이다. */
    Map<String, Object> requestClose(CurrentUser user, Long originExecutionId, List<CdpTarget> tabs);


    /** 그 주소로 새 탭을 여는 카드를 띄운다. 주소는 호출자가 웹 주소인지 확인했다. */
    Map<String, Object> requestOpen(CurrentUser user, Long originExecutionId, String url);
}
