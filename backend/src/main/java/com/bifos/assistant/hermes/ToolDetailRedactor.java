package com.bifos.assistant.hermes;

import com.bifos.assistant.usage.domain.ExecutionEvent;
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
final class ToolDetailRedactor {
    private static final String HIDDEN = "[가림]";
    private static final int INPUT_LIMIT = 65_536;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> SECRET_KEYS = Set.of(
            "token", "secret", "password", "passwd", "apikey", "authorization", "cookie",
            "credential", "credentials", "privatekey", "accesskey", "clientsecret");
    private static final Pattern UUID = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])([A-Za-z0-9_-]+)\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?![A-Za-z0-9_-])");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)Bearer\\s+[^\\s\"'`,;<>}\\]]+"
                    + "|(?i)(?:sk-|gh[pousr]_|github_pat_|xox[baprs]-)[A-Za-z0-9_-]+"
                    + "|(?<![A-Za-z0-9])[A-Fa-f0-9]{32,}(?![A-Za-z0-9])"
                    + "|(?<![A-Za-z0-9_+/=-])[A-Za-z0-9_+/-]{32,}={0,2}(?![A-Za-z0-9_+/=-])");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?[A-Za-z][A-Za-z0-9_-]*[\"']?\\s*[:=]\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;}&]+)");

    static String redact(String detail, boolean connectorManaged) {
        if (detail == null) {
            return null;
        }
        if (connectorManaged) {
            return "[연결 도구 내용 가림]";
        }
        if (detail.length() > INPUT_LIMIT) {
            return "[긴 도구 내용 가림]";
        }
        Map<String, String> identifiers = new LinkedHashMap<>();
        String redacted = redactContent(detail, identifiers);
        if (redacted.length() <= ExecutionEvent.DETAIL_LIMIT) {
            return redacted;
        }
        int end = ExecutionEvent.DETAIL_LIMIT - 1;
        if (Character.isHighSurrogate(redacted.charAt(end - 1))) {
            end--;
        }
        return redacted.substring(0, end) + "…";
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
        return redactText(redactAssignments(detail), identifiers);
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
            return MAPPER.getNodeFactory().stringNode(redactText(redactAssignments(node.asText()), identifiers));
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
        Matcher matcher = ASSIGNMENT.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1).replaceAll("[\"'\\s:=]", "");
            String value = matcher.group(2);
            if (isSecretKey(key) && (value.startsWith("{") || value.startsWith("["))) {
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
        String withoutUuids = UUID.matcher(text).replaceAll(match -> {
            String key = match.group().toLowerCase(Locale.ROOT);
            String label = identifiers.computeIfAbsent(key, ignored -> "[항목 " + (identifiers.size() + 1) + "]");
            return Matcher.quoteReplacement(label);
        });
        String withoutJwt = JWT.matcher(withoutUuids).replaceAll(match -> {
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
