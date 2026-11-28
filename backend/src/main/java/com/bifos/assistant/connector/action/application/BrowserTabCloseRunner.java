package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.browser.application.BrowserAgentControl;
import com.bifos.assistant.browser.application.BrowserTabControl;
import com.bifos.assistant.browser.application.dto.TabCloseResult;
import com.bifos.assistant.browser.application.dto.TabRef;
import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.connector.action.application.port.ControlPlaneActionRunner;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 사용자가 승인한 「내 브라우저」 탭 닫기를 승인 트랜잭션 밖에서 한다. CDP 호출이 몇 초 걸릴 수 있어서다. */
@Component
@RequiredArgsConstructor
class BrowserTabCloseRunner implements ControlPlaneActionRunner {
    private final BrowserAgentControl control;
    private final BrowserTabControl tabs;


    @Override
    public String toolName() {
        return ControlPlaneActions.BROWSER_TAB_CLOSE;
    }


    @Override
    public boolean outsideTransaction() {
        return true;
    }


    @Override
    public ControlPlaneActionOutcome run(CurrentUser user, Map<String, String> args) {
        Optional<ControlPlaneActionOutcome> refused = BrowserTabRunners.refused(control, user);
        if (refused.isPresent()) {
            return refused.get();
        }
        List<TabRef> refs = BrowserTabRunners.refs(args.get(ControlPlaneActions.BROWSER_TARGETS));
        if (refs.isEmpty()) {
            return ControlPlaneActionOutcome.failed("닫을 탭을 읽지 못했어요. 다시 부탁해 주세요");
        }
        TabCloseResult result;
        try {
            result = tabs.close(user.id(), refs);
        } catch (ApiException ex) {
            return BrowserTabRunners.held(ex);
        }
        if (result.closed() == 0) {
            return ControlPlaneActionOutcome.failed("탭을 닫지 않았어요. 탭이 없어졌거나, 다른 페이지로 갔거나, 다른 작업이 쓰는 중이에요");
        }
        String message = "탭 " + result.closed() + "개를 닫았어요";
        if (result.skipped() > 0) {
            message += ". 탭 " + result.skipped() + "개는 없어졌거나, 다른 페이지로 갔거나, 다른 작업이 쓰는 중이라 두었어요";
        }
        return ControlPlaneActionOutcome.done(message);
    }
}
