package com.bifos.assistant.hermes;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 도구 설명을 외부로 중계하거나 저장하기 전에 비밀값과 식별자를 제거한다. */
public final class ToolDetailRedactor {
    /** 가린 뒤 중계하고 저장하는 도구 내용의 글자 상한이다. 실행 사건의 `detail` 열 길이와 같다. */
    public static final int DETAIL_LIMIT = 500;

    private static final String HIDDEN = "[가림]";

    /** 식별자를 번호표로 바꾸지 않고 남기라는 표식이다. 같은 객체인지로 견준다. */
    private static final Map<String, String> KEEP_IDENTIFIERS = new LinkedHashMap<>();

    private static final int INPUT_LIMIT = 65_536;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> SECRET_KEYS = Set.of(
            "token",
            "secret",
            "password",
            "passwd",
            "apikey",
            "authorization",
            "cookie",
            "credential",
            "credentials",
            "privatekey",
            "accesskey",
            "clientsecret");
    private static final Pattern UUID =
            Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern JWT =
            Pattern.compile("(?<![A-Za-z0-9_-])([A-Za-z0-9_-]+)\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?![A-Za-z0-9_-])");
    private static final Pattern TOKEN = Pattern.compile("(?i)Bearer\\s+[^\\s\"'`,;<>}\\]]+"
            + "|(?i)(?:sk-|gh[pousr]_|github_pat_|xox[a-z]*-)[A-Za-z0-9_-]+"
            + "|(?<![A-Za-z0-9])[A-Fa-f0-9]{32,}(?![A-Za-z0-9])"
            + "|(?<![A-Za-z0-9_+/=-])[A-Za-z0-9+/]{32,}={0,2}(?![A-Za-z0-9_+/=-])"
            + "|(?<![A-Za-z0-9_+/=-])[A-Za-z0-9_-]{32,}={0,2}(?![A-Za-z0-9_+/=-])");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)([\"']?[A-Za-z][A-Za-z0-9_-]*[\"']?\\s*[:=]\\s*)"
            + "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;}&]+)");
    private static final Pattern AUTH_HEADER = Pattern.compile("(?im)\\b(authorization|cookie)\\s*[:=]\\s*[^\\r\\n]+");

    static String redact(String detail, boolean connectorManaged) {
        return redact(detail, connectorManaged, new LinkedHashMap<>());
    }

    /** 번호표는 실행 하나 동안만 메모리에 두고 저장하거나 다른 실행과 공유하지 않는다. */
    public static String redact(String detail, boolean connectorManaged, Map<String, String> identifiers) {
        if (detail == null) {
            return null;
        }
        if (connectorManaged) {
            return "[연결 도구 내용 가림]";
        }
        if (detail.length() > INPUT_LIMIT) {
            return "[긴 도구 내용 가림]";
        }
        String redacted = redactContent(detail, identifiers);
        if (redacted.length() <= DETAIL_LIMIT) {
            return redacted;
        }
        int end = DETAIL_LIMIT - 1;
        if (Character.isHighSurrogate(redacted.charAt(end - 1))) {
            end--;
        }
        return redacted.substring(0, end) + "…";
    }

    /**
     * 사용자가 승인하기 전에 읽는 도구 인자에서 비밀값과 식별자를 가린다.
     *
     * <p>사용자는 이 글을 읽고 승인하므로 길이로 자르지 않는다. 인자의 크기는 정책 판정이 이미 제한한다. UUID 는
     * 가리지 않는다. 무엇을 고치거나 지우는지 가리키는 값이라, 가리면 서로 다른 대상의 요청이 같게 보인다. 이 글은
     * 주인만 읽는다.
     */
    public static String redactArguments(String argsJson) {
        return argsJson == null ? null : redactContent(argsJson, KEEP_IDENTIFIERS);
    }

    /**
     * {@link #redactArguments} 가 그 인자에서 무엇이든 가리는가.
     *
     * <p>가린 글은 JSON 을 다시 직렬화한 것이라 공백과 숫자 표기가 원문과 다를 수 있다. 원문을 같은 방법으로 한 번
     * 다시 직렬화한 것과 견줘, 가린 것이 없는 인자가 모양 차이만으로 참이 되지 않게 한다. JSON 으로 읽히지 않는
     * 원문은 통째로 가려지므로 참이다.
     */
    public static boolean hidesArguments(String argsJson) {
        if (argsJson == null) {
            return false;
        }
        String redacted = redactContent(argsJson, KEEP_IDENTIFIERS);
        String stripped = argsJson.stripLeading();
        if (!stripped.startsWith("{") && !stripped.startsWith("[")) {
            return !argsJson.equals(redacted);
        }
        try {
            return !MAPPER.writeValueAsString(MAPPER.readTree(argsJson)).equals(redacted);
        } catch (JacksonException ex) {
            return true;
        }
    }

    private static String redactContent(String detail, Map<String, String> identifiers) {
        String stripped = detail.stripLeading();
        if (stripped.startsWith("{") || stripped.startsWith("[")) {
            try {
                return MAPPER.writeValueAsString(redactJson(MAPPER.readTree(detail), identifiers));
            } catch (JacksonException ex) {
                // 손상된 JSON은 값 전체를 가린다. 파싱 예외와 원문은 저장하거나 기록하지 않는다.
                return HIDDEN;
            }
        }
        return redactAssignments(redactText(detail, identifiers));
    }

    private static JsonNode redactJson(JsonNode node, Map<String, String> identifiers) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            node.properties().forEach(entry -> {
                JsonNode value = isSecretKey(entry.getKey())
                        ? MAPPER.getNodeFactory().stringNode(HIDDEN)
                        : redactJson(entry.getValue(), identifiers);
                result.set(redactText(entry.getKey(), identifiers), value);
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(value -> result.add(redactJson(value, identifiers)));
            return result;
        }
        if (node.isString()) {
            return MAPPER.getNodeFactory().stringNode(redactAssignments(redactText(node.asText(), identifiers)));
        }
        return node;
    }

    private static boolean isSecretKey(String key) {
        String normalized = key.replaceAll("[_-]", "").toLowerCase(Locale.ROOT);
        return SECRET_KEYS.contains(normalized)
                || normalized.endsWith("token")
                || normalized.endsWith("secret")
                || normalized.endsWith("password")
                || normalized.endsWith("privatekey");
    }

    private static String redactAssignments(String text) {
        text = AUTH_HEADER.matcher(text).replaceAll(match -> match.group(1) + ": " + HIDDEN);
        Matcher matcher = ASSIGNMENT.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1).replaceAll("[\"'\\s:=]", "");
            String value = matcher.group(2);
            boolean structuredValue = value.startsWith("{") || value.startsWith("[");
            boolean alreadyHidden = HIDDEN.equals(value);
            if (isSecretKey(key) && structuredValue && !alreadyHidden) {
                return HIDDEN;
            }
        }
        matcher.reset();
        return matcher.replaceAll(match -> {
            String prefix = match.group(1);
            String key = prefix.replaceAll("[\"'\\s:=]", "");
            return Matcher.quoteReplacement(isSecretKey(key) ? prefix + HIDDEN : match.group());
        });
    }

    private static String redactText(String text, Map<String, String> identifiers) {
        if (identifiers == KEEP_IDENTIFIERS) {
            // UUID 는 긴 토큰 모양에도 걸린다. UUID 사이의 글에서만 토큰을 가린다.
            StringBuilder kept = new StringBuilder();
            Matcher uuids = UUID.matcher(text);
            int from = 0;
            while (uuids.find()) {
                kept.append(redactTokens(text.substring(from, uuids.start()))).append(uuids.group());
                from = uuids.end();
            }
            return kept.append(redactTokens(text.substring(from))).toString();
        }
        String withoutUuids = UUID.matcher(text).replaceAll(match -> {
            String key = match.group().toLowerCase(Locale.ROOT);
            String label = identifiers.computeIfAbsent(key, ignored -> "[항목 " + (identifiers.size() + 1) + "]");
            return Matcher.quoteReplacement(label);
        });
        return redactTokens(withoutUuids);
    }

    private static String redactTokens(String text) {
        String withoutJwt = JWT.matcher(text).replaceAll(match -> {
            try {
                JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(match.group(1)));
                if (header != null && header.isObject() && header.has("alg")) {
                    return HIDDEN;
                }
            } catch (IllegalArgumentException | JacksonException ex) {
                // 도메인과 날짜를 JWT로 오인하지 않는다.
            }
            return Matcher.quoteReplacement(match.group());
        });
        return TOKEN.matcher(withoutJwt).replaceAll(HIDDEN);
    }
}
