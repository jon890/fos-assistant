package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectionFieldsConverter;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
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
    /** 비밀 칸에서 저장하는 앞부분의 길이다. */
    private static final int SECRET_PREFIX_LENGTH = 4;

    /** 앞부분을 저장하는 비밀값의 최소 길이다. 이보다 짧으면 앞부분이 원문의 4분의 1 을 넘는다. */
    private static final int SECRET_PREFIX_MIN_VALUE_LENGTH = 16;

    /** 비밀이 아닌 칸 값의 상한이다. 칸 값은 {@code fields} 열 하나에 JSON 으로 함께 들어간다. */
    private static final int MAX_STORED_VALUE_LENGTH = 500;

    /** 비밀 칸 값의 상한이다. 저장하지 않지만 대시보드와 env 파일로 그대로 넘어간다. */
    private static final int MAX_SECRET_VALUE_LENGTH = 4096;

    private static final ConnectionFieldsConverter COLUMN = new ConnectionFieldsConverter();

    /**
     * 요청의 값을 manifest 로 검사하고 채운 칸만 남긴다.
     *
     * <p>비운 선택 칸은 결과에 넣지 않는다. 오류 메시지에는 칸 이름과 값을 싣지 않는다.
     *
     * @param requireAll 등록이면 참. 필수 칸이 모두 있어야 하고, 저장할 칸 값 전체가 {@code fields} 열에 들어가야 한다.
     *     외부에 반영한 뒤 저장에서 실패하지 않도록 여기서 먼저 본다
     */
    static Map<String, String> validated(ConnectorManifest manifest, Map<String, String> values, boolean requireAll) {
        Map<String, String> given = values == null ? Map.of() : values;
        Map<String, ConnectorField> fields =
                manifest.fields().stream().collect(Collectors.toMap(ConnectorField::key, field -> field));
        if (!fields.keySet().containsAll(given.keySet())) {
            throw ConnectorErrors.invalid();
        }
        Map<String, String> accepted = new LinkedHashMap<>();
        for (ConnectorField field : manifest.fields()) {
            String value = given.get(field.key());
            if (value == null || value.isBlank()) {
                if (requireAll && field.required()) {
                    throw ConnectorErrors.invalid();
                }
                continue;
            }
            int limit = field.secret() ? MAX_SECRET_VALUE_LENGTH : MAX_STORED_VALUE_LENGTH;
            if (!matches(field, value) || value.length() > limit) {
                throw ConnectorErrors.invalid();
            }
            accepted.put(field.key(), value);
        }
        if (requireAll
                && COLUMN.convertToDatabaseColumn(stored(manifest, accepted)).length()
                        > ConnectorConnection.FIELDS_LENGTH) {
            throw ConnectorErrors.invalid();
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

    /**
     * 저장할 칸 값이다.
     *
     * <p>비밀 칸은 값이 {@value #SECRET_PREFIX_MIN_VALUE_LENGTH}자 이상일 때만 앞
     * {@value #SECRET_PREFIX_LENGTH}자를 남긴다. 그보다 짧은 값은 앞부분이 원문의 큰 부분이라 아무것도 남기지 않는다.
     */
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
            } else if (value.length() >= SECRET_PREFIX_MIN_VALUE_LENGTH) {
                secretPrefixes.put(field.key(), secretPrefix(value));
            }
        }
        return new ConnectionFields(values, secretPrefixes);
    }

    /** 비밀값의 앞부분이다. 끝 자리에서 대리 쌍이 갈리면 그 앞에서 자른다. */
    private static String secretPrefix(String value) {
        int end = Character.isHighSurrogate(value.charAt(SECRET_PREFIX_LENGTH - 1))
                ? SECRET_PREFIX_LENGTH - 1
                : SECRET_PREFIX_LENGTH;
        return value.substring(0, end);
    }
}
