package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.ConnectorAppearance;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * 카탈로그 항목의 {@code icon} 과 {@code link} 를 다시 검증해 읽는다.
 *
 * <p>규칙은 ADR-20261008 connector-card 의 목록 하나가 정본이고, 대시보드 plugin 의 같은 검사와 같은 입력에 같은 답을
 * 낸다. 틀린 칸은 그 칸만 null 로 두고 커넥터는 그대로 낸다. 장식 칸 하나 때문에 쓰던 연결이 화면에서 사라지면 안 되므로
 * 어떤 입력에도 예외를 밖으로 던지지 않는다.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorAppearances {

    private static final Set<String> ICON_MEDIA_TYPES = Set.of("image/svg+xml", "image/png");

    private static final char BOM = '\uFEFF';

    private static final int ICON_MAX_BYTES = 32 * 1024;

    private static final int LINK_MAX_CHARS = 500;

    /** 상한 바이트를 base64 로 쓴 최대 글자 수다. 이보다 긴 글은 디코딩하지 않고 버린다. */
    private static final int ICON_MAX_BASE64_CHARS = (ICON_MAX_BYTES + 2) / 3 * 4;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * 링크 모양이다. 대시보드가 같은 식을 {@code fullmatch} 로 쓰므로 여기서는 {@code matches()} 로 쓴다.
     *
     * <p>{@code java.net.URI} 같은 해석기는 언어마다 받는 범위가 달라 쓰지 않는다. 식을 바꾸면 대시보드의 식도 함께 바꾼다.
     */
    private static final Pattern LINK = Pattern.compile(
            "^https://[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*"
                    + "(?::[0-9]{1,5})?(?:[/?#](?:[A-Za-z0-9\\-._~!$&'()*+,;=:@/?#]|%[0-9A-Fa-f]{2})*)?$");

    /** 대시보드와 같은 금지 목록이다. 일부만 걸려도 버리므로 {@code find()} 로 쓴다. 공백과 대소문자는 ASCII 기준이다. */
    private static final Pattern SVG_FORBIDDEN = Pattern.compile(
            "<!doctype|<!entity|<script|<foreignobject|<iframe|<embed|<object|<set|<animate|@import|javascript:|&#|\\\\"
                    + "|\\son[a-z]+\\s*=|href\\s*=(?![\\s\"']*#)|url\\((?![\\s\"']*#)",
            Pattern.CASE_INSENSITIVE);

    /** 카탈로그 항목 하나에서 아이콘과 링크를 읽는다. 없거나 틀린 칸은 null 이다. */
    static ConnectorAppearance read(JsonNode item) {
        JsonNode icon = item.get("icon");
        JsonNode link = item.get("link");
        if (absent(icon) && absent(link)) {
            return ConnectorAppearance.NONE;
        }
        return new ConnectorAppearance(field(item, "icon", icon), field(item, "link", link));
    }

    private static String field(JsonNode item, String name, JsonNode declared) {
        if (absent(declared)) {
            return null;
        }
        String value;
        try {
            value = "icon".equals(name) ? icon(declared) : link(declared);
        } catch (RuntimeException ex) {
            value = null;
        }
        if (value == null) {
            log.warn("커넥터 카드 칸을 버렸다 connector={} field={}", id(item), name);
        }
        return value;
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }

    private static String id(JsonNode item) {
        JsonNode id = item.get("id");
        return id != null && id.isString() ? id.asString() : null;
    }

    private static String icon(JsonNode declared) {
        if (!declared.isObject()) {
            return null;
        }
        JsonNode mediaType = declared.get("media_type");
        JsonNode data = declared.get("data");
        if (mediaType == null || !mediaType.isString() || data == null || !data.isString()) {
            return null;
        }
        String type = mediaType.asString();
        if (!ICON_MEDIA_TYPES.contains(type)) {
            return null;
        }
        String encoded = data.asString();
        if (encoded.length() > ICON_MAX_BASE64_CHARS) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length == 0 || bytes.length > ICON_MAX_BYTES) {
            return null;
        }
        boolean safe = "image/png".equals(type) ? startsWithPngSignature(bytes) : svgIsSafe(bytes);
        // 검사한 바이트를 다시 인코딩해 담는다. 패딩 없는 입력도 표준 base64 로 낸다.
        return safe ? "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes) : null;
    }

    private static boolean startsWithPngSignature(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean svgIsSafe(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            return false;
        }
        return startsWithSvgRoot(text) && !SVG_FORBIDDEN.matcher(text).find();
    }

    /**
     * 앞의 BOM, 공백, XML 선언, 주석을 건너뛴 뒤 {@code <svg} 루트가 오는지 본다.
     *
     * <p>정규식이 아니라 한 번 훑는 반복이다. {@code (<!--.*?-->\s*)*} 같은 정규식은 주석이 많은 입력에서 되추적으로
     * {@code StackOverflowError} 를 내 카탈로그 전체를 실패시킨다.
     */
    private static boolean startsWithSvgRoot(String text) {
        int at = !text.isEmpty() && text.charAt(0) == BOM ? 1 : 0;
        int before;
        do {
            before = at;
            while (at < text.length() && isAsciiSpace(text.charAt(at))) {
                at++;
            }
            if (text.startsWith("<?xml", at)) {
                int end = text.indexOf("?>", at + "<?xml".length());
                if (end < 0) {
                    return false;
                }
                at = end + "?>".length();
            } else if (text.startsWith("<!--", at)) {
                int end = text.indexOf("-->", at + "<!--".length());
                if (end < 0) {
                    return false;
                }
                at = end + "-->".length();
            }
        } while (at != before);
        return isSvgTagAt(text, at);
    }

    /** 대소문자는 ASCII 로만 무시한다. {@code String.regionMatches} 는 유니코드 대소문자를 써 대시보드와 답이 갈린다. */
    private static boolean isSvgTagAt(String text, int at) {
        String tag = "<svg";
        if (at + tag.length() >= text.length()) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            char c = text.charAt(at + i);
            char lower = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
            if (lower != tag.charAt(i)) {
                return false;
            }
        }
        char next = text.charAt(at + tag.length());
        return isAsciiSpace(next) || next == '>' || next == '/';
    }

    private static boolean isAsciiSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\u000B';
    }

    private static String link(JsonNode declared) {
        if (!declared.isString()) {
            return null;
        }
        String value = declared.asString();
        if (value.codePointCount(0, value.length()) > LINK_MAX_CHARS) {
            return null;
        }
        return LINK.matcher(value).matches() ? value : null;
    }
}
