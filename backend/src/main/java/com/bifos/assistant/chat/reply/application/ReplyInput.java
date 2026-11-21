package com.bifos.assistant.chat.reply.application;

import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.GeneratedImageTitle;
import com.bifos.assistant.chat.artifact.domain.MediaPathMask;
import com.bifos.assistant.chat.attachment.application.AttachmentService;
import com.bifos.assistant.chat.attachment.application.dto.ExtraImage;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.ReplyRef;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.reply.application.dto.ReplySection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 답장으로 집은 것을 실행 입력의 「사용자가 집은 메시지」 구역과 함께 넣을 이미지로 만든다(스펙 chat-reference REQ7, REQ8, REQ15).
 *
 * <p>보낼 때와 다시 만들 때 모두 질문 줄의 칸으로 만든다. 다시 만들 때는 검사하지 않으므로 쓸 수 없게 된 사진과 그림은 빼고 그
 * 사실을 구역에 적는다. 답 글은 이력처럼 그림 경로를 가린다. 경로가 남으면 모델이 그 경로를 그림 도구에 적고 fos-ctx 가 막는다.
 */
@Component
@RequiredArgsConstructor
public class ReplyInput {

    /** 집은 글을 넣는 상한(글자)이다. Hermes 요청 본문 10MB 안에 이미지 예산 7MB 와 함께 든다(설계 chat-reference DECISION7). */
    static final int QUOTE_LIMIT = 20_000;

    private final ChatMessageRepository messages;
    private final AttachmentService attachments;
    private final GeneratedImageService generatedImages;


    /** 답장이 아니면 빈 구역이다. */
    public ReplySection section(Long conversationId, ReplyRef reply) {
        if (reply == null) {
            return ReplySection.EMPTY;
        }
        Optional<ChatMessage> found = messages.findById(reply.messageId())
            .filter(it -> conversationId.equals(it.conversationId()));
        StringBuilder text = new StringBuilder("[사용자가 집은 메시지]\n")
            .append("사용자가 이 메시지를 가리키며 아래 글을 보냈다. 「이것」, 「이 그림」, 「이 글」 은 이 메시지다.\n");
        if (found.isEmpty()) {
            return new ReplySection(text.append("원래 메시지를 찾을 수 없다. 집은 메시지를 보았다고 하지 않는다.\n\n").toString(), List.of(), List.of());
        }
        ChatMessage message = found.get();
        boolean answer = message.role() == MessageRole.ASSISTANT;
        String quoted = answer ? MediaPathMask.strip(message.content()) : message.content();
        if (quoted.length() > QUOTE_LIMIT) {
            quoted = quoted.substring(0, QUOTE_LIMIT) + "\n(뒤를 잘랐다)";
        }
        text.append("쓴 사람: ").append(answer ? "비서" : "사용자").append('\n')
            .append("<quoted-message>\n").append(quoted).append("\n</quoted-message>\n");
        List<ExtraImage> extras = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Map<Long, Integer> order = attachments.orderInConversation(conversationId);
        for (ChatAttachment photo : pickedPhotos(conversationId, message, reply)) {
            String name = order.getOrDefault(photo.id(), 0) + "번째 사진(attachment:" + photo.id() + ")";
            if (attachments.inspectable(photo)) {
                labels.add("집은 그림 " + (extras.size() + 1));
                extras.add(ExtraImage.ofAttachment(photo));
                lines.add("- " + name + ": 입력 이미지 「" + labels.getLast() + "」");
            } else {
                lines.add("- " + name + ": 보관 기간이 지났거나 지워져 쓸 수 없다");
            }
        }
        for (ChatArtifact image : pickedImages(message, reply)) {
            String name = "그림 「" + GeneratedImageTitle.ofPath(image.path()) + "」(generated:" + GeneratedImageTitle.ofPath(image.path()) + ")";
            if (generatedImages.isLive(image)) {
                labels.add("집은 그림 " + (extras.size() + 1));
                // 원본은 줄일 차례가 올 때 한 장씩 읽는다
                extras.add(ExtraImage.ofOriginal(() -> generatedImages.readLive(image)));
                lines.add("- " + name + ": 입력 이미지 「" + labels.getLast() + "」");
            } else {
                lines.add("- " + name + ": 보관 기간이 지났거나 지워져 쓸 수 없다");
            }
        }
        if (!lines.isEmpty()) {
            text.append(reply.wholeMessage() ? "이 메시지의 사진과 그림:\n" : "집은 사진이나 그림:\n")
                .append(String.join("\n", lines)).append('\n')
                .append("입력 이미지가 없으면 넣지 못한 것이니 보았다고 하지 않는다. 그림을 고치거나 참고할 때는 괄호 안의 가리킴을 쓴다.\n");
            if (lines.size() > 1) {
                text.append("사진이나 그림이 여럿인데 사용자 글이 어느 것인지 말하지 않으면 먼저 되묻는다.\n");
            }
        }
        return new ReplySection(text.append('\n').toString(), extras, labels);
    }


    private List<ChatAttachment> pickedPhotos(Long conversationId, ChatMessage message, ReplyRef reply) {
        if (reply.artifactId() != null) {
            return List.of();
        }
        return attachments.allOf(conversationId).stream()
            .filter(it -> message.id().equals(it.messageId()))
            .filter(it -> reply.attachmentId() == null || reply.attachmentId().equals(it.id()))
            .toList();
    }


    private List<ChatArtifact> pickedImages(ChatMessage message, ReplyRef reply) {
        if (reply.attachmentId() != null) {
            return List.of();
        }
        return generatedImages.imagesOfMessage(message.id()).stream()
            .filter(it -> reply.artifactId() == null || reply.artifactId().equals(it.id()))
            .toList();
    }
}
