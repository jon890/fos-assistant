package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.browser.application.BrowserAgentControl;
import com.bifos.assistant.browser.application.BrowserTabControl;
import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.connector.action.application.port.ControlPlaneActionRunner;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 사용자가 승인한 「내 브라우저」 탭 열기를 승인 트랜잭션 밖에서 한다. 꺼진 브라우저는 켠다. */
@Component
@RequiredArgsConstructor
class BrowserTabOpenRunner implements ControlPlaneActionRunner {
    private final BrowserAgentControl control;
    private final BrowserTabControl tabs;


    @Override
    public String toolName() {
        return ControlPlaneActions.BROWSER_TAB_OPEN;
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
        try {
            tabs.open(user.id(), args.getOrDefault("url", ""));
        } catch (IllegalArgumentException ex) {
            return ControlPlaneActionOutcome.failed("열 수 없는 주소예요");
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.BROWSER_CAPACITY || ex.code() == ErrorCode.BROWSER_START_FAILED
                || ex.code() == ErrorCode.BROWSER_BUSY) {
                return ControlPlaneActionOutcome.failed("브라우저를 켤 수 없어요. 잠시 뒤 다시 부탁해 주세요");
            }
            return BrowserTabRunners.held(ex);
        }
        return ControlPlaneActionOutcome.done("내 브라우저에 탭을 열었어요");
    }
}
