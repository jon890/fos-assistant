package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사진마다 에이전트에게 보일 줄인 사본을 만들고, 보내는 메시지의 사진 가운데 실행 입력에 실을 것을 고른다. 근거는
 * ADR-20261009 / native-image-input 에 있다.
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

    /** 한 턴에 싣는 사진 수 상한이다. 같은 턴의 도구 호출마다 이미지가 다시 실리므로 토큰을 묶는다. */
    static final int MAX_IMAGES = 10;

    /**
     * 한 턴에 싣는 {@code data:} 주소 길이 합의 상한이다. Hermes 요청 본문 상한 10MB 에서 기억 문맥과 지시문, 입력 글의
     * 자리를 남긴다.
     */
    static final long MAX_ENCODED_BYTES = 7L * 1024 * 1024;

    private static final String DATA_URL_PREFIX = "data:image/jpeg;base64,";

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

    /**
     * 보내는 메시지의 사진마다 에이전트에게 어떻게 보일지를 첨부 순서대로 정한다.
     *
     * <p>{@code embed} 가 참이면 사본이 없는 사진은 한 번 만들어 보고, 앞 사진부터 상한({@link #MAX_IMAGES},
     * {@link #MAX_ENCODED_BYTES})까지 사본을 싣는다. 한 번 상한에 닿으면 그 뒤 사진은 더 작아도 싣지 않는다. 화면 순서와
     * 실린 순서를 같게 두기 위해서다. 상한 뒤의 사진도 사본을 만들어 본다. 경로로 안내할 사본이 있어야 한다.
     *
     * <p>{@code embed} 가 거짓이면 사본을 만들지도 싣지도 않고, 있는 사본만 본다.
     *
     * <p>사진 한 장의 실패는 삼킨다. 사본을 읽지 못하면 그 사진을 싣지 않고 원본 이름으로 안내한다.
     *
     * @param order 첨부 번호마다 그 대화에서 몇 번째 사진인지. 보낸 사진은 모두 들어 있어야 한다
     */
    public List<AgentPhoto> photos(List<ChatAttachment> attached, Map<Long, Integer> order, boolean embed) {
        List<AgentPhoto> photos = new ArrayList<>(attached.size());
        int acceptedImages = 0;
        long acceptedEncodedBytes = 0;
        boolean admitting = embed;
        for (ChatAttachment attachment : attached) {
            Integer ordinal = order.get(attachment.id());
            if (ordinal == null) {
                throw new IllegalStateException("attachment " + attachment.id() + " is not bound to a message");
            }
            boolean small = embed ? prepareSmall(attachment) : hasSmallQuietly(attachment);
            String dataUrl = null;
            if (small && admitting) {
                String encoded = encodedSmall(attachment);
                if (encoded == null) {
                    small = false;
                } else if (admits(
                        acceptedImages, acceptedEncodedBytes, encoded.length(), MAX_IMAGES, MAX_ENCODED_BYTES)) {
                    dataUrl = encoded;
                    acceptedImages++;
                    acceptedEncodedBytes += encoded.length();
                } else {
                    admitting = false;
                }
            }
            String agentFileName = small ? AttachmentStore.smallName(attachment.id()) : attachment.storedName();
            photos.add(new AgentPhoto(attachment.id(), ordinal, agentFileName, dataUrl));
        }
        return photos;
    }

    /** 이미 {@code acceptedImages} 장, {@code acceptedEncodedBytes} 길이를 담았을 때 다음 사진을 더 담을 수 있으면 참이다. */
    static boolean admits(
            int acceptedImages, long acceptedEncodedBytes, long nextEncodedBytes, int maxImages, long maxEncodedBytes) {
        return acceptedImages < maxImages && acceptedEncodedBytes + nextEncodedBytes <= maxEncodedBytes;
    }

    private boolean hasSmallQuietly(ChatAttachment attachment) {
        try {
            return store.hasSmall(attachment);
        } catch (RuntimeException ex) {
            log.warn(
                    "could not check the small copy attachmentId={} error={}",
                    attachment.id(),
                    ex.getClass().getSimpleName());
            return false;
        }
    }

    /** 사본을 {@code data:} 주소로 읽는다. 읽지 못하면 null 이다. */
    private String encodedSmall(ChatAttachment attachment) {
        try {
            return DATA_URL_PREFIX + Base64.getEncoder().encodeToString(store.readSmall(attachment));
        } catch (RuntimeException ex) {
            log.warn(
                    "could not read the small copy attachmentId={} error={}",
                    attachment.id(),
                    ex.getClass().getSimpleName());
            return null;
        }
    }
}
