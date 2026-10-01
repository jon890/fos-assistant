package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 요청이 준 칸 값을 manifest 로 검사하고, 저장할 모양으로 바꾼다.
 *
 * <p>비밀 칸의 원문이 저장되지 않게 하는 규칙이 여기 한 곳에 있다(ADR-043).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorValues {
    /** 비밀 칸에서 저장하는 앞부분의 길이다. 값이 이보다 길 때만 저장한다. */
    private static final int SECRET_PREFIX_LENGTH = 8;

    /** 비밀이 아닌 칸 값의 상한이다. 칸 값은 {@code fields} 열 하나에 JSON 으로 함께 들어간다. */
    private static final int MAX_STORED_VALUE_LENGTH = 500;

    /**
     * 요청의 값을 manifest 로 검사하고 채운 칸만 남긴다.
     *
     * <p>비운 선택 칸은 결과에 넣지 않는다. 오류 메시지에는 칸 이름과 값을 싣지 않는다.
     */
    static Map<String, String> validated(ConnectorManifest manifest, Map<String, String> values, boolean requireAll) {
        Map<String, String> given = values == null ? Map.of() : values;
        Map<String, ConnectorField> fields =
                manifest.fields().stream().collect(Collectors.toMap(ConnectorField::key, field -> field));
        if (!fields.keySet().containsAll(given.keySet())) {
            throw invalid();
        }
        Map<String, String> accepted = new LinkedHashMap<>();
        for (ConnectorField field : manifest.fields()) {
            String value = given.get(field.key());
            if (value == null || value.isBlank()) {
                if (requireAll && field.required()) {
                    throw invalid();
                }
                continue;
            }
            if (!matches(field, value) || !field.secret() && value.length() > MAX_STORED_VALUE_LENGTH) {
                throw invalid();
            }
            accepted.put(field.key(), value);
        }
        return accepted;
    }

    private static boolean matches(ConnectorField field, String value) {
        // 줄바꿈과 NUL 은 env 파일의 다른 줄을 쓰게 만들 수 있다.
        if (value.chars().anyMatch(ch -> ch == '\r' || ch == '\n' || ch == 0)) {
            return false;
        }
        if (field.pattern() == null) {
            return true;
        }
        try {
            return Pattern.compile(field.pattern()).matcher(value).matches();
        } catch (PatternSyntaxException ex) {
            // manifest 의 형식은 대시보드가 쓰는 정규식 문법이다. 여기서 읽지 못하면 대시보드의 검사에 맡긴다.
            return true;
        }
    }

    /** 저장할 칸 값이다. 비밀 칸은 앞부분만 남기고, 앞부분이 원문 전체인 짧은 값은 남기지 않는다. */
    static ConnectionFields stored(ConnectorManifest manifest, Map<String, String> accepted) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> secretPrefixes = new LinkedHashMap<>();
        for (ConnectorField field : manifest.fields()) {
            String value = accepted.get(field.key());
            if (value == null) {
                continue;
            }
            if (!field.secret()) {
                values.put(field.key(), value);
            } else if (value.length() > SECRET_PREFIX_LENGTH) {
                secretPrefixes.put(field.key(), value.substring(0, SECRET_PREFIX_LENGTH));
            }
        }
        return new ConnectionFields(values, secretPrefixes);
    }

    static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "connector values are invalid");
    }
}
