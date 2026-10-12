package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.entity.ContentDraftAsset;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사진 처리 단계마다 본문 없는 사건을 한 줄로 남긴다. 첨부 번호와 자리, 장수만 싣고 사진 바이트·캡션·원고 글·파일 이름은 싣지 않는다.
 *
 * <p>줄의 모양은 {@code media-pipeline {"schemaVersion":1,...}} 이다. 합성 E2E 가 이 줄을 모아 단계별 증거로 판정한다.
 * 사건은 그 단계가 실제로 일어났다는 사실만 뜻한다. 모델이 사진을 읽었다는 증거가 아니다.
 */
public final class MediaPipelineEvents {

    private static final Logger log = LoggerFactory.getLogger(MediaPipelineEvents.class);

    private MediaPipelineEvents() {}

    /**
     * Control Plane 이 실행 입력에 이미지로 실은 사진과 싣지 못한 사진을 남긴다. 자리는 실린 이미지 목록 안의 순서다.
     * 입력을 만든 사실만 뜻하며 Hermes 나 provider 가 이미지를 받았다는 뜻이 아니다.
     */
    public static void embedded(Long conversationId, List<AgentPhoto> photos) {
        if (photos.isEmpty()) {
            return;
        }
        var embedded = photos.stream().filter(AgentPhoto::embedded).map(AgentPhoto::attachmentId).toList();
        var missing = photos.stream().filter(photo -> !photo.embedded()).map(AgentPhoto::attachmentId).toList();
        log.info("media-pipeline {}", "{\"schemaVersion\":1,\"stage\":\"embedded\",\"conversationId\":" + conversationId
            + ",\"count\":" + embedded.size() + ",\"assets\":" + positions(embedded) + ",\"missing\":" + ids(missing) + "}");
    }

    /**
     * 원고 revision 이 참조한 사진을 커밋 뒤에 남긴다. 자리는 블록 목록 안의 순서다. 롤백한 저장은 남기지 않는다.
     * 원고가 사진을 참조했다는 사실만 뜻하며 그 사진의 내용과 원고 글이 맞는다는 증거가 아니다.
     */
    public static void draftReferenced(Long conversationId, String draftId, long revision, List<ContentDraftAsset> assets) {
        String assetList = assets.stream()
            .map(asset -> "{\"assetId\":\"" + asset.attachmentId() + "\",\"position\":" + asset.position() + "}")
            .collect(Collectors.joining(",", "[", "]"));
        String message = "{\"schemaVersion\":1,\"stage\":\"draftReferenced\",\"conversationId\":" + conversationId
            + ",\"draftId\":\"" + draftId + "\",\"revision\":" + revision + ",\"count\":" + assets.size()
            + ",\"assets\":" + assetList + "}";
        afterCommit(() -> log.info("media-pipeline {}", message));
    }

    private static String positions(List<Long> ids) {
        var result = new StringBuilder("[");
        for (int position = 0; position < ids.size(); position++) {
            if (position > 0) {
                result.append(',');
            }
            result.append("{\"assetId\":\"").append(ids.get(position)).append("\",\"position\":").append(position).append('}');
        }
        return result.append(']').toString();
    }

    private static String ids(List<Long> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private static void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                work.run();
            }
        });
    }
}
