package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.BrowserAgentControl;
import com.bifos.assistant.browser.application.BrowserHumanHold;
import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.browser.application.dto.BrowserScreenInput;
import com.bifos.assistant.browser.application.dto.UserBrowserSnapshot;
import com.bifos.assistant.browser.presentation.UserBrowserDtos.AgentControlRequest;
import com.bifos.assistant.browser.presentation.UserBrowserDtos.AgentControlView;
import com.bifos.assistant.browser.presentation.UserBrowserDtos.BrowserView;
import com.bifos.assistant.browser.presentation.UserBrowserDtos.ScreenInputRequest;
import com.bifos.assistant.browser.presentation.UserBrowserDtos.TakeoverRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사람이 웹에서 쓰는 내 브라우저 경로다. 계약은 {@code docs/features/user-browser.md} 의 「API(사용자 브라우저)」 가 갖는다.
 *
 * <p>주인은 웹 토큰의 사용자다. 요청 본문으로 사용자나 브라우저를 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/browser")
@RequiredArgsConstructor
public class UserBrowserController {
    private final UserBrowserService browsers;
    private final CurrentUserProvider currentUser;
    private final BrowserHumanHold holds;
    private final BrowserAgentControl agentControl;


    /** 기능이 꺼져 있어도 200 이다. 화면이 「준비 중」 을 그릴 수 있게 한다. */
    @GetMapping
    public BrowserView get() {
        Long userId = currentUser.require().id();
        if (!browsers.enabled()) {
            return BrowserView.disabled();
        }
        return browsers.get(userId)
            .map(this::view)
            .orElseGet(() -> BrowserView.missing(browsers.idleTimeout()));
    }


    /**
     * 에이전트가 내 브라우저를 쓰게 하는 스위치를 바꾼다. 끄기는 늘 된다. 브라우저가 없으면 {@code BROWSER_NOT_FOUND}, 관리자가 꺼 두었는데 켜면
     * {@code BROWSER_AGENT_CONTROL_OFF} 다.
     */
    @PutMapping("/agent-control")
    public BrowserView agentControl(@RequestBody(required = false) AgentControlRequest body) {
        CurrentUser user = currentUser.require();
        requireEnabled();
        agentControl.setUser(user, Optional.ofNullable(body).orElse(new AgentControlRequest(null)).required());
        return browsers.get(user.id()).map(this::view).orElseGet(() -> BrowserView.missing(browsers.idleTimeout()));
    }


    @PostMapping
    public BrowserView create() {
        return view(browsers.create(currentUser.require().id()));
    }


    @PostMapping("/start")
    public BrowserView start() {
        return view(browsers.start(currentUser.require().id()));
    }


    @PostMapping("/stop")
    public BrowserView stop() {
        return view(browsers.stop(currentUser.require().id()));
    }


    @DeleteMapping
    public ResponseEntity<Void> delete() {
        browsers.delete(currentUser.require().id());
        return ResponseEntity.noContent().build();
    }


    /** 로그인 화면을 연다. 꺼져 있으면 켜고 지금 탭에 붙어 SSE 를 연다. 시작 주소는 {@code http}, {@code https} 만 받는다. */
    @GetMapping(path = "/screen", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter screen(@RequestParam(required = false) String url) {
        Long userId = currentUser.require().id();
        if (url != null && !BrowserScreenInput.webUrl(url)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "screen url is not valid");
        }
        BrowserScreenStream stream = new BrowserScreenStream();
        browsers.openScreen(userId, url, stream);
        return stream.emitter();
    }


    /** 요청자의 열린 화면에 입력 하나를 보낸다. 본문은 직접 읽어 값이 오류 응답과 로그에 실리지 않게 한다. */
    @PostMapping("/screen/input")
    public ResponseEntity<Void> input(@RequestBody(required = false) String body) {
        Long userId = currentUser.require().id();
        browsers.screenInput(userId, ScreenInputRequest.parse(body));
        return ResponseEntity.noContent().build();
    }


    /**
     * 「직접 할게요」 다. 사람이 브라우저를 쥐고, 쥔 동안 에이전트의 요청은 「사람이 브라우저를 쓰는 중」 넘기기로 끝난다. 이미 쥐었으면 그대로
     * 204 다. 브라우저를 쓰는 승인한 실행이 도는 중이면 {@code BROWSER_APPROVAL_RUNNING} 이다.
     */
    @PostMapping("/takeover")
    public ResponseEntity<Void> takeOver(@RequestBody(required = false) TakeoverRequest body) {
        Long userId = currentUser.require().id();
        requireEnabled();
        holds.takeOver(userId, TakeoverRequest.origin(body));
        return ResponseEntity.noContent().build();
    }


    /** 「돌려주기」 다. 잠금만 푼다. 글을 보낼지는 화면이 정한다. 쥐지 않았어도 204 다. */
    @PostMapping("/give-back")
    public ResponseEntity<Void> giveBack() {
        Long userId = currentUser.require().id();
        requireEnabled();
        holds.giveBack(userId);
        return ResponseEntity.noContent().build();
    }


    private BrowserView view(UserBrowserSnapshot browser) {
        CurrentUser user = currentUser.require();
        var control = new AgentControlView(agentControl.userEnabled(user.id()), agentControl.adminAllowed(user.groupId()));
        return BrowserView.of(browser, browsers.idleTimeout(), control);
    }


    private void requireEnabled() {
        if (!browsers.enabled()) {
            throw new ApiException(ErrorCode.BROWSER_DISABLED, "user browser is disabled");
        }
    }
}
