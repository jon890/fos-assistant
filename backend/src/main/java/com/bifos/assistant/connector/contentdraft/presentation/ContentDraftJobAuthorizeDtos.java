package com.bifos.assistant.connector.contentdraft.presentation;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 원고 저장 authorize 의 요청과 응답 모양이다. 요청은 원문을 strict 하게 읽는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ContentDraftJobAuthorizeDtos {
    public static final int REQUEST_LIMIT = 8192;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(1).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();


    /** ticket 은 문자열 표현과 로그에 싣지 않는다. */
    public record AuthorizeRequest(String ticket, String stage, Integer position) {


        private static final Set<String> KEYS = Set.of("ticket", "stage");
        private static final Set<String> PHOTO_KEYS = Set.of("ticket", "stage", "position");

        /** 타입 보정, 중복 키, 추가 키와 뒤따르는 JSON 을 받지 않는다. */
        public static AuthorizeRequest decode(byte[] raw) {
            try {
                require(raw.length <= REQUEST_LIMIT);
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw));
                var node = JSON.readTree(raw);
                require(node != null && node.isObject());
                var names = node.propertyNames();
                require(names.equals(KEYS) || names.equals(PHOTO_KEYS));
                require(node.get("ticket").isString() && node.get("stage").isString());
                Integer position = null;
                if (node.has("position")) {
                    require(node.get("position").isInt());
                    position = node.get("position").intValue();
                }
                return new AuthorizeRequest(node.get("ticket").stringValue(), node.get("stage").stringValue(), position);
            } catch (Exception ignored) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "content save authorize request rejected");
            }
        }


        @Override
        public String toString() {
            return "AuthorizeRequest[redacted]";
        }


        private static void require(boolean condition) {
            if (!condition) {
                throw new IllegalArgumentException("content save authorize request rejected");
            }
        }
    }

    public record AuthorizeResponse(boolean allowed) {
        public static final AuthorizeResponse ALLOWED = new AuthorizeResponse(true);
    }
}
