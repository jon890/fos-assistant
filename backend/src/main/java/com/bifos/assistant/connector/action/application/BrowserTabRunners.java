package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.browser.application.BrowserAgentControl;
import com.bifos.assistant.browser.application.dto.TabRef;
import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * {@link BrowserTabCloseRunner} 와 {@link BrowserTabOpenRunner} 가 함께 쓰는 판정과 글이다(docs/features/agent-browser/design.md 의
 * 「PR1 도구」 실행 순서). 실행 직전에 스위치를 다시 보고, 꺼졌으면 아무것도 하지 않는다. 결과 글에 탭 제목과 주소를 넣지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BrowserTabRunners {
    static final String SWITCHED_OFF = "에이전트의 브라우저 조작이 꺼져 있어 하지 않았어요. 내 브라우저 화면에서 켠 뒤 다시 부탁해 주세요";
    static final String HELD = "직접 쓰고 계신 브라우저를 돌려준 뒤 다시 부탁해 주세요";


    /** 스위치가 꺼졌으면 실패 결과다. */
    static Optional<ControlPlaneActionOutcome> refused(BrowserAgentControl control, CurrentUser user) {
        return control.status(user).allowed() ? Optional.empty() : Optional.of(ControlPlaneActionOutcome.failed(SWITCHED_OFF));
    }


    /** 사람 잠금이면 실패 결과로 바꾸고, 다른 오류는 그대로 던진다. 승인 서비스가 정해 둔 실패 글로 적는다. */
    static ControlPlaneActionOutcome held(ApiException ex) {
        if (ex.code() == ErrorCode.BROWSER_HUMAN_HELD) {
            return ControlPlaneActionOutcome.failed(HELD);
        }
        throw ex;
    }


    /** 닫기 카드의 실행 칸을 읽는다. 모양이 틀린 조각은 버린다. */
    static List<TabRef> refs(String targets) {
        List<TabRef> refs = new ArrayList<>();
        if (targets == null || targets.isBlank()) {
            return refs;
        }
        for (String part : targets.split(",")) {
            int colon = part.indexOf(':');
            if (colon > 0 && colon < part.length() - 1) {
                refs.add(new TabRef(part.substring(0, colon), part.substring(colon + 1)));
            }
        }
        return refs;
    }
}
