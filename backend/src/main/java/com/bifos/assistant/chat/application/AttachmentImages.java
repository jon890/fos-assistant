package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사진마다 에이전트에게 보일 줄인 사본을 만들고, 보내는 메시지의 사진을 실행 입력에 어느 크기로 얼마나 실을지 고른다.
 * 화소 합과 {@code data:} 주소 길이 합의 예산 안에서 모든 사진을 같은 긴 변 단계로 싣는다. 근거는 ADR-20261009 /
 * native-image-input 에 있다.
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

    /**
     * 보낼 때 사본을 만들려고 디코딩 차례를 기다리는 시간(초)이다. 보낼 때는 실행 기록을 만들기 전이라 사본 없는 사진마다
     * 30초를 기다리면 실행 시작이 크게 늦고 그동안 사용자가 중지할 수도 없다. 차례를 얻지 못한 사진은 싣지 않고 원본 경로로
     * 안내한다.
     */
    static final long SEND_WAIT_SECONDS = 5;

    /**
     * 한 턴에 싣는 사진의 화소 합 상한이다. 1600×1200 열 장이다. 같은 턴의 도구 호출마다 이미지가 다시 실리므로 도구 호출 한
     * 번의 이미지 토큰을 묶는다. 근거는 ADR-20261009 / native-image-input 의 「상한의 근거」 에 있다.
     */
    static final long MAX_PIXELS = 19_200_000L;

    /** 사진을 싣는 긴 변 단계(px)다. 큰 것부터 본다. */
    static final List<Integer> LONG_SIDES = List.of(AgentImageResizer.LONG_SIDE, 1280, 1024, 768);

    /**
     * 한 턴에 싣는 {@code data:} 주소 길이 합의 상한이다. Hermes 요청 본문 상한 10MB 에서 기억 문맥과 지시문, 입력 글의
     * 자리를 남긴다.
     */
    static final long MAX_ENCODED_BYTES = 7L * 1024 * 1024;

    private static final String DATA_URL_PREFIX = "data:image/jpeg;base64,";

    private final AttachmentStore store;
    private final Semaphore decoding = new Semaphore(DECODING_SLOTS);

    /**
     * 줄인 사본이 없으면 만든다. 올린 뒤에 부르며, 디코딩 차례를 30초까지 기다린다. 트랜잭션 밖에서 부른다. 디코딩하는
     * 동안 데이터베이스 연결을 쥐지 않게 하기 위해서다.
     *
     * @return 사본이 있으면 참. 차례를 얻지 못했거나 만들지 못했으면 거짓
     */
    public boolean prepareSmall(ChatAttachment attachment) {
        return prepareSmall(attachment, DECODING_WAIT_SECONDS);
    }

    /**
     * 줄인 사본이 없으면 만든다. 디코딩 차례를 {@code waitSeconds} 초까지 기다린다. 트랜잭션 밖에서 부른다.
     *
     * @return 사본이 있으면 참. 차례를 얻지 못했거나 만들지 못했으면 거짓
     */
    public boolean prepareSmall(ChatAttachment attachment, long waitSeconds) {
        try {
            if (store.hasSmall(attachment)) {
                return true;
            }
            if (!decoding.tryAcquire(waitSeconds, TimeUnit.SECONDS)) {
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
     * <p>{@code embed} 가 참이면 사본이 없는 사진은 디코딩 차례를 {@link #SEND_WAIT_SECONDS} 초까지 기다려 한 번 만들어
     * 본다. 사본을 읽고 크기를 안 사진을 모두 같은 긴 변 단계({@link #LONG_SIDES})로 싣는다. 화소 합이
     * {@link #MAX_PIXELS} 안에 드는 가장 큰 단계부터 보고, {@code data:} 주소 길이 합이 {@link #MAX_ENCODED_BYTES} 를 넘으면
     * 한 단계 내린다. 1600 단계는 사본을 그대로 싣고, 그보다 작은 단계는 사본을 다시 줄여 싣는다. 다시 줄인 것은 파일로
     * 남기지 않는다.
     *
     * <p>가장 작은 단계에서도 넘치면 앞 사진부터 두 상한까지 담는다. 한 번 상한에 닿으면 그 뒤 사진은 더 작아도 싣지 않는다.
     * 화면 순서와 실린 순서를 같게 두기 위해서다. 싣지 못한 사진도 사본을 만들어 본다. 경로로 안내할 사본이 있어야 한다.
     *
     * <p>{@code embed} 가 거짓이면 사본을 만들지도 싣지도 않고, 있는 사본만 본다.
     *
     * <p>사진 한 장의 실패는 삼킨다. 사본을 읽지 못하면 그 사진을 싣지 않고 원본 이름으로 안내한다. 사본의 크기를 모르거나
     * 다시 줄이지 못하면 그 사진을 싣지 않고 사본 이름으로 안내한다.
     *
     * @param order 첨부 번호마다 그 대화에서 몇 번째 사진인지. 보낸 사진은 모두 들어 있어야 한다
     */
    public List<AgentPhoto> photos(List<ChatAttachment> attached, Map<Long, Integer> order, boolean embed) {
        List<Integer> ordinals = new ArrayList<>(attached.size());
        List<String> agentFileNames = new ArrayList<>(attached.size());
        List<Candidate> candidates = new ArrayList<>();
        for (ChatAttachment attachment : attached) {
            Integer ordinal = order.get(attachment.id());
            if (ordinal == null) {
                throw new IllegalStateException("attachment " + attachment.id() + " is not bound to a message");
            }
            boolean small = embed ? prepareSmall(attachment, SEND_WAIT_SECONDS) : hasSmallQuietly(attachment);
            if (small && embed) {
                byte[] copy = readSmallQuietly(attachment);
                small = copy != null;
                int index = ordinals.size();
                if (small) {
                    AgentImageResizer.dimensions(copy)
                            .ifPresent(size -> candidates.add(new Candidate(index, attachment.id(), copy, size)));
                }
            }
            ordinals.add(ordinal);
            agentFileNames.add(small ? AttachmentStore.smallName(attachment.id()) : attachment.storedName());
        }
        String[] dataUrls = new String[attached.size()];
        int longSide = embedCandidates(candidates, dataUrls);
        List<AgentPhoto> photos = new ArrayList<>(attached.size());
        for (int i = 0; i < attached.size(); i++) {
            photos.add(new AgentPhoto(
                    attached.get(i).id(),
                    ordinals.get(i),
                    agentFileNames.get(i),
                    dataUrls[i],
                    dataUrls[i] == null ? 0 : longSide));
        }
        return photos;
    }

    /** 긴 변을 {@code longSide} 로 줄였을 때의 화소 수다. 작은 사진은 키우지 않으므로 받은 크기 그대로 센다. */
    static long pixelsAt(Dimension size, int longSide) {
        Dimension scaled = AgentImageResizer.scaledSize(size.width, size.height, longSide);
        return (long) scaled.width * scaled.height;
    }

    /**
     * {@link #LONG_SIDES} 를 큰 것부터 보며 모든 사진의 화소 합이 {@code maxPixels} 이하인 첫 단계를 고른다. 어느 단계도
     * 들지 않으면 가장 작은 단계다. 사진이 없으면 가장 큰 단계다.
     */
    static int chooseLongSide(List<Dimension> sizes, long maxPixels) {
        for (int longSide : LONG_SIDES) {
            long total = sizes.stream().mapToLong(size -> pixelsAt(size, longSide)).sum();
            if (total <= maxPixels) {
                return longSide;
            }
        }
        return LONG_SIDES.getLast();
    }

    /**
     * 이미 화소 {@code acceptedPixels}, 길이 {@code acceptedEncodedBytes} 를 담았을 때 다음 사진을 더 담을 수 있으면 참이다. 두
     * 합이 모두 상한 이하여야 한다.
     */
    static boolean admits(
            long acceptedPixels,
            long acceptedEncodedBytes,
            long nextPixels,
            long nextEncodedBytes,
            long maxPixels,
            long maxEncodedBytes) {
        return acceptedPixels + nextPixels <= maxPixels && acceptedEncodedBytes + nextEncodedBytes <= maxEncodedBytes;
    }

    /**
     * 후보를 한 단계로 싣는다. 실은 사진의 {@code data:} 주소를 {@code dataUrls} 의 제자리에 채우고 그 단계를 돌려준다.
     * 가장 작은 단계보다 큰 단계는 만든 주소를 모두 담을 수 있을 때만 고른다.
     */
    private int embedCandidates(List<Candidate> candidates, String[] dataUrls) {
        int first = LONG_SIDES.indexOf(chooseLongSide(
                candidates.stream().map(Candidate::size).toList(), MAX_PIXELS));
        for (int step = first; step < LONG_SIDES.size() - 1; step++) {
            int longSide = LONG_SIDES.get(step);
            List<String> encoded = encodeAt(candidates, longSide, MAX_ENCODED_BYTES);
            if (encoded != null) {
                List<String> admitted = admitInOrder(candidates, encoded, longSide);
                if (admitted.equals(encoded)) {
                    place(candidates, admitted, dataUrls);
                    return longSide;
                }
            }
        }
        int smallest = LONG_SIDES.getLast();
        List<String> encoded = encodeAt(candidates, smallest, Long.MAX_VALUE);
        place(candidates, admitInOrder(candidates, encoded, smallest), dataUrls);
        return smallest;
    }

    /**
     * 후보마다 긴 변 {@code longSide} 의 {@code data:} 주소를 만든다. 만들지 못한 사진의 자리는 null 이다. 길이 합이
     * {@code maxEncodedBytes} 를 넘으면 남은 사진을 줄이지 않고 null 을 돌려준다. 이 단계로는 모두 싣지 못하기 때문이다.
     */
    private List<String> encodeAt(List<Candidate> candidates, int longSide, long maxEncodedBytes) {
        List<String> encoded = new ArrayList<>(candidates.size());
        long total = 0;
        for (Candidate candidate : candidates) {
            String dataUrl = encodedAt(candidate, longSide);
            if (dataUrl != null) {
                total += dataUrl.length();
                if (total > maxEncodedBytes) {
                    return null;
                }
            }
            encoded.add(dataUrl);
        }
        return encoded;
    }

    /**
     * 사본 하나를 긴 변 {@code longSide} 의 {@code data:} 주소로 만든다. 줄일 필요가 없으면 사본을 그대로 쓰고, 줄여야 하면
     * 디코딩 차례를 얻어 다시 줄인다. 차례를 얻지 못했거나 줄이지 못했으면 null 이다.
     */
    private String encodedAt(Candidate candidate, int longSide) {
        Dimension size = candidate.size();
        if (AgentImageResizer.scaledSize(size.width, size.height, longSide).equals(size)) {
            return dataUrl(candidate.copy());
        }
        try {
            if (!decoding.tryAcquire(SEND_WAIT_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
            try {
                Optional<byte[]> shrunk = AgentImageResizer.shrink(candidate.copy(), longSide);
                if (shrunk.isEmpty()) {
                    log.warn(
                            "could not shrink the small copy attachmentId={} longSide={}",
                            candidate.attachmentId(),
                            longSide);
                }
                return shrunk.map(AttachmentImages::dataUrl).orElse(null);
            } finally {
                decoding.release();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 앞 사진부터 {@link #admits} 로 담는다. 주소가 없는 사진은 건너뛰고, 담지 못하는 사진을 만나면 그 뒤는 담지 않는다. 담은
     * 사진의 자리에만 주소가 있다.
     */
    private static List<String> admitInOrder(List<Candidate> candidates, List<String> encoded, int longSide) {
        List<String> admitted = new ArrayList<>(Collections.nCopies(encoded.size(), (String) null));
        long pixels = 0;
        long encodedBytes = 0;
        for (int i = 0; i < encoded.size(); i++) {
            String dataUrl = encoded.get(i);
            if (dataUrl == null) {
                continue;
            }
            long nextPixels = pixelsAt(candidates.get(i).size(), longSide);
            if (!admits(pixels, encodedBytes, nextPixels, dataUrl.length(), MAX_PIXELS, MAX_ENCODED_BYTES)) {
                break;
            }
            admitted.set(i, dataUrl);
            pixels += nextPixels;
            encodedBytes += dataUrl.length();
        }
        return admitted;
    }

    private static void place(List<Candidate> candidates, List<String> admitted, String[] dataUrls) {
        for (int i = 0; i < candidates.size(); i++) {
            dataUrls[candidates.get(i).index()] = admitted.get(i);
        }
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

    /** 사본을 읽는다. 읽지 못하면 null 이다. */
    private byte[] readSmallQuietly(ChatAttachment attachment) {
        try {
            return store.readSmall(attachment);
        } catch (RuntimeException ex) {
            log.warn(
                    "could not read the small copy attachmentId={} error={}",
                    attachment.id(),
                    ex.getClass().getSimpleName());
            return null;
        }
    }

    private static String dataUrl(byte[] jpeg) {
        return DATA_URL_PREFIX + Base64.getEncoder().encodeToString(jpeg);
    }

    /**
     * 실을 수 있는 사진이다.
     *
     * @param index 보낸 사진 가운데 몇 번째인지(0부터)
     * @param copy 사본 JPEG 바이트
     * @param size 사본의 가로와 세로
     */
    private record Candidate(int index, Long attachmentId, byte[] copy, Dimension size) {}
}
