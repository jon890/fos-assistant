package com.bifos.assistant.connector.domain;

import com.bifos.assistant.shared.util.Sha256;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Hermes 가 MCP 도구에 붙이는 등록 이름을 계산한다.
 *
 * <p>규칙은 {@code docs/hermes/connector-policy.md} 의 「MCP 도구의 등록 이름」 이 갖는다. 대시보드 plugin 이 이름
 * 대응 파일을 만들 때 쓰는 계산과 같은 값을 내야 한다. hook 이 보낸 원래 도구 이름을 그대로 믿지 않고, 이 값이 hook 이
 * 받은 등록 이름과 같은지 견주는 데 쓴다(ADR-048).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HermesToolName {
    private static final int MAX_CHARS = 64;
    private static final int HASH_CHARS = 8;
    private static final String OUTSIDE = "[^A-Za-z0-9_]";

    /** 이은 이름이 상한을 넘으면 앞부분에 {@code _} 와 이름 전체를 SHA-256 한 16진수의 앞 8자를 붙인다. */
    public static String of(String server, String tool) {
        String full = "mcp__" + server.replaceAll(OUTSIDE, "_") + "__" + tool.replaceAll(OUTSIDE, "_");
        if (full.length() <= MAX_CHARS) {
            return full;
        }
        return full.substring(0, MAX_CHARS - HASH_CHARS - 1)
                + "_"
                + Sha256.hex(full).substring(0, HASH_CHARS);
    }
}
