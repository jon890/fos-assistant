package com.bifos.assistant.task.infra;

import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskContentOpener;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 예약 작업의 제목과 지시문을 저장할 때 암호화하고 읽을 때 푼다(ADR-20261008 / data-encryption).
 *
 * <p>AAD 는 {@code task:<작업 번호>:title:user:<작업 주인>} 과 {@code task:<작업 번호>:instruction:user:<작업 주인>} 이다.
 * 데이터베이스에서 암호문을 다른 줄이나 다른 칸으로 옮기거나 주인을 바꾸면 풀리지 않고 {@link Task#UNREADABLE_CONTENT} 가 보인다.
 * 작업 줄이 주인을 갖고 있어 따로 읽지 않는다. 점검 작업의 지시문은 없으므로(null) 암호화하지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskContents implements TaskContentOpener {

    private final TextCipher cipher;


    /** 새 글을 암호화해 저장하는가. 거짓이면 평문으로 저장한다 */
    public boolean sealing() {
        return cipher.enabled();
    }


    /**
     * 줄 번호가 있는 작업의 제목과 지시문을 암호화해 적는다. 그 칸에 지금 들어 있는 평문이 대상이다.
     *
     * <p>암호화가 꺼져 있으면 아무것도 하지 않는다.
     */
    public void seal(Task task) {
        if (!cipher.enabled()) {
            return;
        }
        Long owner = task.ownerUserId();
        String title = task.title();
        SealedText sealedTitle = cipher.seal(owner, aad(task, "title"), title)
            .orElseThrow(() -> new IllegalStateException("task encryption is not enabled"));
        String instruction = task.instruction();
        SealedText sealedInstruction = instruction == null
            ? null
            : cipher.seal(owner, aad(task, "instruction"), instruction)
                .orElseThrow(() -> new IllegalStateException("task encryption is not enabled"));
        task.sealContent(
            sealedTitle.content(),
            sealedTitle.keyId(),
            sealedInstruction == null ? null : sealedInstruction.content(),
            sealedInstruction == null ? null : sealedInstruction.keyId());
    }


    /** 방금 적은 암호문이 그 평문으로 풀리는지 본다. 이미 있는 줄을 암호화하는 배치가 옛 글을 잃지 않게 한다. */
    public boolean opensTo(Task task, String title, String instruction) {
        boolean titleOk = cipher.open(task.titleKeyId(), task.ownerUserId(), aad(task, "title"), task.storedTitle())
            .filter(title::equals)
            .isPresent();
        if (task.instructionKeyId() == null) {
            return titleOk && instruction == null && task.storedInstruction() == null;
        }
        return titleOk
            && cipher.open(task.instructionKeyId(), task.ownerUserId(), aad(task, "instruction"), task.storedInstruction())
                .filter(opened -> opened.equals(instruction))
                .isPresent();
    }


    /**
     * 암호문 제목과 지시문을 풀어 평문과 빈 key 칸으로 되돌린다. 옛 버전으로 내리기 전의 풀기 배치만 쓴다.
     *
     * @return 하나라도 풀지 못하면 거짓이고 줄은 그대로다
     */
    public boolean unseal(Task task) {
        Optional<String> title = cipher.open(task.titleKeyId(), task.ownerUserId(), aad(task, "title"), task.storedTitle());
        if (title.isEmpty()) {
            return false;
        }
        String instruction = task.storedInstruction();
        if (task.instructionKeyId() != null) {
            Optional<String> opened = cipher.open(
                task.instructionKeyId(), task.ownerUserId(), aad(task, "instruction"), task.storedInstruction());
            if (opened.isEmpty()) {
                return false;
            }
            instruction = opened.get();
        }
        task.unsealContent(title.get(), instruction);
        return true;
    }


    @Override
    public String openTitle(Task task) {
        return open(task, "title", task.titleKeyId(), task.storedTitle());
    }


    @Override
    public String openInstruction(Task task) {
        return open(task, "instruction", task.instructionKeyId(), task.storedInstruction());
    }


    private String open(Task task, String field, Long keyId, String sealed) {
        return cipher.open(keyId, task.ownerUserId(), aad(task, field), sealed)
            .orElseGet(() -> {
                log.warn("예약 작업 글을 풀지 못했다 taskId={} field={}", task.id(), field);
                return Task.UNREADABLE_CONTENT;
            });
    }


    private static String aad(Task task, String field) {
        return "task:" + task.id() + ":" + field + ":user:" + task.ownerUserId();
    }
}
