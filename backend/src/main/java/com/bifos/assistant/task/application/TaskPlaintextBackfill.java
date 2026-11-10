package com.bifos.assistant.task.application;

import com.bifos.assistant.crypto.application.dto.BackfillDirection;
import com.bifos.assistant.crypto.config.DataEncryptionProperties;
import com.bifos.assistant.crypto.domain.BackfillColumn;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.task.infra.TaskRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 키를 넣기 전에 저장한 예약 작업의 제목과 지시문을 매분 조금씩 암호화하고, 되돌릴 때는 평문으로 되돌린다(ADR-20261008 / data-encryption).
 *
 * <p>방향과 양, 멈춘 자리에서 이어지는 방식은 {@code ChatPlaintextBackfill} 과 같다. 제목과 지시문은 한 줄에서 함께 고친다. 로그에 본문을
 * 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TaskPlaintextBackfill {

    private final TaskRepository tasks;
    private final TaskPlaintextSealer sealer;
    private final TextCipher cipher;
    private final DataEncryptionProperties properties;

    /** 번호 커서를 들고 있어 빈을 만들 때 한 번 만든다. */
    private BackfillColumn contentColumn;


    @PostConstruct
    void createColumn() {
        this.contentColumn = new BackfillColumn(
            "task.title+instruction",
            (after, limit) -> tasks.findPlainIds(after, PageRequest.ofSize(limit)),
            (after, limit) -> tasks.findSealedIds(after, PageRequest.ofSize(limit)),
            tasks::countPlain,
            tasks::countSealed,
            sealer::seal,
            sealer::unseal);
    }


    /** 운영은 매분 돈다. 검사에서는 {@code -} 로 끄고 {@link #run} 을 직접 부른다. */
    @Scheduled(cron = "${assistant.data-encryption.backfill-cron}")
    public void runScheduled() {
        run(properties.backfill(), properties.backfillBatch());
    }


    /**
     * 한 차례 돈다.
     *
     * @param limit 고치는 줄 수의 상한
     * @return 고친 줄 수
     */
    public int run(BackfillDirection direction, int limit) {
        if (direction == BackfillDirection.OFF) {
            return 0;
        }
        return contentColumn.run(direction == BackfillDirection.SEAL, limit, cipher.enabled());
    }
}
