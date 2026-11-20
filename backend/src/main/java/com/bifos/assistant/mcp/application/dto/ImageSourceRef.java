package com.bifos.assistant.mcp.application.dto;

import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageMention;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import tools.jackson.databind.JsonNode;

/**
 * 모델이 {@code image_generate} 의 바탕이나 참고로 적은 가리킴 하나다(ADR-20261120 / image-source).
 *
 * <p>{@code attachment:<첨부 번호>} 는 그 대화의 사진이고 {@code generated:<제목>} 은 그 대화에서 만든 그림이다.
 * {@code link:<주소>} 는 사용자가 그 대화에 보낸 링크다(ADR-20261120 / link-image-source). 셋 가운데 하나만 차 있다.
 * 모양이 틀리면 {@link ErrorCode#IMAGE_SOURCE_UNKNOWN} 이다.
 */
public record ImageSourceRef(Long attachmentId, String title, String link) {


    private static final String ATTACHMENT = "attachment:";
    private static final String GENERATED = "generated:";
    private static final String LINK = "link:";
    private static final int MAX_CHARS = 300;
    /** 링크 가리킴의 길이 상한이다. 상품 주소는 쿼리가 길어 첨부와 제목의 상한보다 크게 둔다. */
    private static final int MAX_LINK_CHARS = GeneratedImageMention.MAX_LINK_CHARS + LINK.length();

    public static ImageSourceRef parse(JsonNode value) {
        if (value == null || !value.isString()) {
            throw unknown();
        }
        String raw = value.asString().strip();
        if (raw.startsWith(LINK)) {
            String link = raw.substring(LINK.length()).strip();
            if (link.isEmpty() || raw.length() > MAX_LINK_CHARS) {
                throw unknown();
            }
            return new ImageSourceRef(null, null, link);
        }
        if (raw.length() > MAX_CHARS) {
            throw unknown();
        }
        if (raw.startsWith(ATTACHMENT)) {
            String number = raw.substring(ATTACHMENT.length()).strip();
            if (!number.matches("[1-9][0-9]{0,17}")) {
                throw unknown();
            }
            return new ImageSourceRef(Long.parseLong(number), null, null);
        }
        if (raw.startsWith(GENERATED)) {
            String title = raw.substring(GENERATED.length()).strip();
            if (title.isEmpty()) {
                throw unknown();
            }
            return new ImageSourceRef(null, title, null);
        }
        throw unknown();
    }


    private static ApiException unknown() {
        return new ApiException(ErrorCode.IMAGE_SOURCE_UNKNOWN, "this image is not in the conversation");
    }
}
