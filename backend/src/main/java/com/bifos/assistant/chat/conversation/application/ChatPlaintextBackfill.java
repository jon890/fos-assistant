package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.crypto.application.dto.BackfillDirection;
import com.bifos.assistant.crypto.config.DataEncryptionProperties;
import com.bifos.assistant.crypto.domain.BackfillColumn;
import com.bifos.assistant.crypto.domain.TextCipher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 키를 넣기 전에 저장한 메시지 본문과 대화 제목을 매분 조금씩 암호화하고, 되돌릴 때는 평문으로 되돌린다(ADR-20261008 / data-encryption).
 *
 * <p>기동 때 한꺼번에 돌지 않는다. 대상은 key 칸이 빈 줄이라 멈춘 자리에서 저절로 이어진다. 방향과 양은
 * {@code assistant.data-encryption.backfill} 과 {@code backfill-batch} 가 정한다. 로그에 본문을 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatPlaintextBackfill {

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatPlaintextSealer sealer;
    private final TextCipher cipher;
    private final DataEncryptionProperties properties;

    /** 칸마다 번호 커서를 들고 있어 빈을 만들 때 한 번 만든다. */
    private BackfillColumn messageColumn;
    private BackfillColumn titleColumn;


    @PostConstruct
    void createColumns() {
        this.messageColumn = new BackfillColumn(
            "chat_message.content",
            (after, limit) -> messages.findPlainIds(after, PageRequest.ofSize(limit)),
            (after, limit) -> messages.findSealedIds(after, PageRequest.ofSize(limit)),
            messages::countPlain,
            messages::countSealed,
            sealer::sealMessage,
            sealer::unsealMessage);
        this.titleColumn = new BackfillColumn(
            "conversation.title",
            (after, limit) -> conversations.findPlainTitleIds(after, PageRequest.ofSize(limit)),
            (after, limit) -> conversations.findSealedTitleIds(after, PageRequest.ofSize(limit)),
            conversations::countPlainTitles,
            conversations::countSealedTitles,
            sealer::sealTitle,
            sealer::unsealTitle);
    }


    /** 운영은 매분 돈다. 검사에서는 {@code -} 로 끄고 {@link #run} 을 직접 부른다. */
    @Scheduled(cron = "${assistant.data-encryption.backfill-cron}")
    public void runScheduled() {
        run(properties.backfill(), properties.backfillBatch());
    }


    /**
     * 칸마다 한 차례 돈다.
     *
     * @param limit 칸마다 고치는 줄 수의 상한
     * @return 고친 줄 수
     */
    public int run(BackfillDirection direction, int limit) {
        if (direction == BackfillDirection.OFF) {
            return 0;
        }
        boolean sealing = direction == BackfillDirection.SEAL;
        boolean enabled = cipher.enabled();
        return messageColumn.run(sealing, limit, enabled) + titleColumn.run(sealing, limit, enabled);
    }
}
