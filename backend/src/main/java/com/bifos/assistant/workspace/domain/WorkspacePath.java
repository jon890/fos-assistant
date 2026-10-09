package com.bifos.assistant.workspace.domain;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 주인 디렉터리 안의 상대 경로다. 빈 조각 목록은 주인 디렉터리 자체다.
 *
 * <p>거절 규칙은 {@code docs/code-architecture.md} 의 「경로 규칙」 이 갖는다. 만들 때 검사하므로 이 값은 늘 규칙을 지킨다.
 * 이 값은 주인을 정하지 않는다. 주인 디렉터리는 요청자로만 정한다.
 */
public record WorkspacePath(List<String> segments) {

    private static final int MAX_BYTES = 4_096;
    private static final int MAX_SEGMENT_BYTES = 255;
    private static final int MAX_SEGMENTS = 64;

    public WorkspacePath {
        segments = List.copyOf(segments);
        if (segments.size() > MAX_SEGMENTS) {
            throw invalid("too many path segments");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || hasControl(segment)) {
                throw invalid("path segment is not allowed");
            }
            if (bytes(segment) > MAX_SEGMENT_BYTES) {
                throw invalid("path segment is too long");
            }
        }
        if (bytes(String.join("/", segments)) > MAX_BYTES) {
            throw invalid("path is too long");
        }
    }

    /** 목록의 {@code path} 인자를 읽는다. {@code null} 과 빈 값은 주인 디렉터리다. */
    public static WorkspacePath parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new WorkspacePath(List.of());
        }
        if (bytes(raw) > MAX_BYTES) {
            throw invalid("path is too long");
        }
        return new WorkspacePath(List.of(raw.split("/", -1)));
    }

    /** 본문 경로처럼 이미 조각으로 나눈 값을 받는다. 검사는 {@link #parse} 와 같다. */
    public static WorkspacePath ofSegments(List<String> segments) {
        return new WorkspacePath(segments);
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    /** 조각을 {@code /} 로 이은 값이다. 주인 디렉터리는 빈 문자열이다. */
    public String value() {
        return String.join("/", segments);
    }

    /** 마지막 조각이다. 주인 디렉터리는 빈 문자열이다. */
    public String name() {
        return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
    }

    /**
     * 이름을 본문 주소의 조각으로 쓸 수 있는지다. {@code %}, {@code ;}, {@code \} 는 Control Plane 의 요청 방화벽이 주소에서
     * 거절하므로 그런 이름은 미리보기와 내려받기를 열지 않는다.
     */
    public static boolean addressable(String name) {
        return name.indexOf('%') < 0 && name.indexOf(';') < 0 && name.indexOf('\\') < 0 && !hasControl(name);
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
