package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사진마다 에이전트에게 보일 줄인 사본을 만든다. 근거는 ADR-20261009 / native-image-input 에 있다.
 *
 * <p>사본은 최선 노력이다. 만들지 못해도 부른 쪽은 실패하지 않고, 경고 로그에는 첨부 번호와 예외 종류만 남긴다.
 * 사진 본문과 올린 이름은 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentImages {

    /** 동시에 디코딩하는 사진 수다. 한 장의 디코딩이 수십 MB 를 쓰므로 동시 업로드가 힙을 한꺼번에 쓰지 않게 한다. */
    private static final int DECODING_SLOTS = 2;

    /**
     * 디코딩 차례를 기다리는 시간(초)이다. 화면은 고른 사진을 모두 동시에 올리므로 바로 건너뛰면 둘을 뺀 나머지에
     * 사본이 생기지 않는다.
     */
    private static final long DECODING_WAIT_SECONDS = 30;

    private final AttachmentStore store;
    private final Semaphore decoding = new Semaphore(DECODING_SLOTS);

    /**
     * 줄인 사본이 없으면 만든다. 트랜잭션 밖에서 부른다. 디코딩하는 동안 데이터베이스 연결을 쥐지 않게 하기 위해서다.
     *
     * @return 사본이 있으면 참. 차례를 얻지 못했거나 만들지 못했으면 거짓
     */
    public boolean prepareSmall(ChatAttachment attachment) {
        try {
            if (store.hasSmall(attachment)) {
                return true;
            }
            if (!decoding.tryAcquire(DECODING_WAIT_SECONDS, TimeUnit.SECONDS)) {
                return false;
            }
            try {
                byte[] original;
                try (InputStream in = store.open(attachment)) {
                    original = in.readAllBytes();
                }
                AgentImageResizer.toJpeg(original).ifPresent(jpeg -> store.saveSmall(attachment, jpeg));
            } finally {
                decoding.release();
            }
            return store.hasSmall(attachment);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException ex) {
            log.warn(
                    "could not prepare the small copy attachmentId={} error={}",
                    attachment.id(),
                    ex.getClass().getSimpleName());
            return false;
        }
    }
}
