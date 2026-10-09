package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.browser.application.model.BrowserScreenInput;
import com.bifos.assistant.browser.presentation.UserBrowserDtos.BrowserView;
import com.bifos.assistant.browser.presentation.UserBrowserDtos.ScreenInputRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사람이 웹에서 쓰는 내 브라우저 경로다. 계약은 {@code backend/docs/flow.md} 의 「API(사용자 브라우저)」 가 갖는다.
 *
 * <p>주인은 웹 토큰의 사용자다. 요청 본문으로 사용자나 브라우저를 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/browser")
@RequiredArgsConstructor
public class UserBrowserController {
    private final UserBrowserService browsers;
    private final CurrentUserProvider currentUser;

    /** 기능이 꺼져 있어도 200 이다. 화면이 「준비 중」 을 그릴 수 있게 한다. */
    @GetMapping
    public BrowserView get() {
        Long userId = currentUser.require().id();
        if (!browsers.enabled()) {
            return BrowserView.disabled();
        }
        return browsers.get(userId)
                .map(browser -> BrowserView.of(browser, browsers.idleTimeout()))
                .orElseGet(() -> BrowserView.missing(browsers.idleTimeout()));
    }

    @PostMapping
    public BrowserView create() {
        return BrowserView.of(browsers.create(currentUser.require().id()), browsers.idleTimeout());
    }

    @PostMapping("/start")
    public BrowserView start() {
        return BrowserView.of(browsers.start(currentUser.require().id()), browsers.idleTimeout());
    }

    @PostMapping("/stop")
    public BrowserView stop() {
        return BrowserView.of(browsers.stop(currentUser.require().id()), browsers.idleTimeout());
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
}
