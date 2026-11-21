package com.bifos.assistant.chat.reply.application.dto;

import com.bifos.assistant.chat.attachment.application.dto.ExtraImage;
import java.util.List;

/**
 * 답장으로 집은 것을 실행 입력의 질문 앞에 넣을 구역이다(스펙 chat-reference REQ7).
 *
 * @param text 구역 글. 답장이 아니면 빈 글이다
 * @param extras 함께 넣을 이미지
 * @param labels {@code extras} 의 같은 자리의 이름표
 */
public record ReplySection(String text, List<ExtraImage> extras, List<String> labels) {

    public static final ReplySection EMPTY = new ReplySection("", List.of(), List.of());
}
