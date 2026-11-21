package com.bifos.assistant.chat.turn.presentation;

import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.MediaPathMask;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.ReplyRef;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.turn.presentation.ChatDtos.ReplyImageView;
import com.bifos.assistant.chat.turn.presentation.ChatDtos.ReplyView;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 이력의 사용자 메시지마다 답장 상자를 만든다(스펙 chat-reference REQ6, REQ13, 설계 DECISION6).
 *
 * <p>원래 메시지와 그 사진, 그림은 이력이 이미 읽은 맵에서 찾는다. 같은 대화 안에서는 메시지를 하나씩 지우는 길이 없어 원래 줄은 늘
 * 있다. 그림과 사진은 보관 기간이 지나면 지운 표시가 된다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ReplyViews {

    /** 상자 글의 줄마다 상한(글자)이다. */
    static final int LINE_LIMIT = 200;


    /** 답장이 아닌 메시지는 null 이다. */
    static ReplyView of(
        ChatMessage message,
        Map<Long, ChatMessage> byId,
        Map<Long, List<ChatAttachment>> attached,
        Map<Long, List<ChatArtifact>> produced) {
        ReplyRef reply = message.reply();
        if (reply == null) {
            return null;
        }
        ChatMessage original = byId.get(reply.messageId());
        if (original == null) {
            return new ReplyView(reply.messageId(), null, "", false, List.of());
        }
        String text = original.role() == MessageRole.ASSISTANT ? MediaPathMask.strip(original.content()) : original.content();
        List<ReplyImageView> photos = reply.artifactId() != null
            ? List.of()
            : attached.getOrDefault(original.id(), List.of()).stream()
                .filter(it -> reply.attachmentId() == null || reply.attachmentId().equals(it.id()))
                .map(it -> new ReplyImageView("ATTACHMENT", null, it.id(), !it.isVisible()))
                .toList();
        List<ReplyImageView> images = reply.attachmentId() != null
            ? List.of()
            : produced.getOrDefault(original.id(), List.of()).stream()
                .filter(ChatArtifact::isGeneratedImage)
                .filter(it -> reply.artifactId() == null || reply.artifactId().equals(it.id()))
                .map(it -> new ReplyImageView("GENERATED", it.path(), null, it.isDeleted()))
                .toList();
        return new ReplyView(
            original.id(), original.role().name(), excerpt(text), true, Stream.concat(photos.stream(), images.stream()).toList());
    }


    /** 빈 줄이 아닌 앞 두 줄이다. 줄마다 {@link #LINE_LIMIT} 자에서 자른다. */
    static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .limit(2)
            .map(line -> line.length() > LINE_LIMIT ? line.substring(0, LINE_LIMIT) + "…" : line)
            .collect(Collectors.joining("\n"));
    }
}
