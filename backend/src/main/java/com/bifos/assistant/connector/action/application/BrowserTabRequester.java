package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.browser.application.BrowserTabControl;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.mcp.application.port.BrowserTabRequests;
import com.bifos.assistant.runtime.domain.ToolDetailRedactor;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 「브라우저 탭 닫기」 와 「브라우저 탭 열기」 승인 카드를 띄운다(docs/features/agent-browser/design.md 의 「PR1 도구」).
 *
 * <p>닫기 카드는 탭마다 「제목 · 사이트」 한 줄을 보이고, 닫을 탭은 카드에 보이지 않는 실행 칸에 탭 번호와 주소 지문으로 둔다. 열기 카드는
 * 주소를 그대로 보이고, 그 주소가 카드 가림에 걸리면 사람이 다 보지 못하므로 카드를 띄우지 않는다.
 */
@Component
@RequiredArgsConstructor
class BrowserTabRequester implements BrowserTabRequests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ControlPlaneApprovals approvals;


    @Override
    public Map<String, Object> requestClose(CurrentUser user, Long originExecutionId, List<CdpTarget> tabs) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("tabs", tabs.stream().map(BrowserTabRequester::line).collect(Collectors.joining("\n")));
        args.put(ControlPlaneActions.BROWSER_TARGETS, tabs.stream()
            .map(tab -> tab.id() + ":" + BrowserTabControl.urlHash(tab.url()))
            .collect(Collectors.joining(",")));
        String target = tabs.stream().map(CdpTarget::id).sorted().collect(Collectors.joining(","));
        var request = approvals.request(user, originExecutionId, ControlPlaneActions.BROWSER_TAB_CLOSE, target, args);
        return answer(request.status(), "「브라우저 탭 닫기」 승인 카드를 띄웠다. 아직 닫지 않았다. 사용자가 카드에서 승인해야 닫힌다.");
    }


    @Override
    public Map<String, Object> requestOpen(CurrentUser user, Long originExecutionId, String url) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("url", url);
        if (ToolDetailRedactor.hidesArguments(JSON.writeValueAsString(args))) {
            return result(NOT_DISPLAYABLE, "주소의 일부가 카드에서 가려져 사용자가 다 볼 수 없어 카드를 띄우지 않았다. 열지 않았다고 알리고, "
                + "사용자가 직접 열도록 주소를 안내하지 않는다.");
        }
        var request = approvals.request(user, originExecutionId, ControlPlaneActions.BROWSER_TAB_OPEN,
            BrowserTabControl.urlHash(url), args);
        return answer(request.status(), "「브라우저 탭 열기」 승인 카드를 띄웠다. 아직 열지 않았다. 사용자가 카드에서 승인해야 열린다.");
    }


    private static Map<String, Object> answer(ActionStatus status, String requested) {
        if (status == ActionStatus.PENDING) {
            return result(REQUESTED, requested);
        }
        return result(CLOSED, "같은 요청의 승인 카드가 이미 끝났다(" + status.name() + "). 다시 하려면 사용자의 다음 요청에서 부른다.");
    }


    /** 카드의 한 줄이다. 사이트는 서버가 주소에서 꺼낸 호스트다. 제목은 페이지가 정한 글이라 사이트와 함께 보인다. */
    static String line(CdpTarget tab) {
        String title = tab.title() == null || tab.title().isBlank() ? "(제목 없음)" : tab.title().strip();
        return title + " · " + host(tab.url());
    }


    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "(주소 없음)" : host;
        } catch (IllegalArgumentException ex) {
            return "(주소 없음)";
        }
    }


    private static Map<String, Object> result(String status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("message", message);
        return body;
    }
}
