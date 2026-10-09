package com.bifos.assistant.shared.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 에이전트가 만든 파일을 사람에게 줄 때 붙이는 {@code Content-Security-Policy} 값이다.
 *
 * <p>결과물과 실행 공간 파일이 같은 값을 쓴다. 근거는 ADR-027 과 ADR-20261009 / workspace-explorer 에 있다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SandboxedContentPolicy {

    /** HTML 미리보기. 스크립트를 막고 같은 출처의 사진과 스타일만 부르게 한다. */
    public static final String HTML = "sandbox allow-same-origin allow-popups "
            + "allow-popups-to-escape-sandbox; default-src 'none'; img-src 'self' data:; "
            + "style-src 'self' 'unsafe-inline'; base-uri 'none'; form-action 'none'";

    /** HTML 이 아닌 미리보기와 내려받기. 아무것도 부르지 못하게 한다. */
    public static final String NONE = "sandbox; default-src 'none'";
}
