package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.contentdraft.application.ContentDraftImages;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftImageView;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftInput;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 만든 그림을 블로그 원고에 넣는 사진 첨부 사본을 확인한다(스펙 generated-image REQ18, REQ19, ADR-20261120 / generated-image-draft).
 */
class ContentDraftImagesTest extends ContentDraftFixture {
    @Autowired
    ContentDraftImages images;

    @Autowired
    GeneratedImageService generated;

    @Autowired
    ArtifactStore artifactStore;


    @AfterEach
    void removeArtifactFolder() {
        artifactStore.deleteFolder(conversation.id());
    }


    @Test
    @DisplayName("그림의 사본은 원본과 바이트가 같고 그 번호로 원고를 저장하며 다시 넣어도 사본이 하나다")
    void copiesGeneratedImageIntoDraft() throws Exception {
        byte[] png = png(51);
        generated.save(conversation.id(), owner.id(), "call-" + UUID.randomUUID(), "수채화 고양이", "창가의 고양이", png);

        ContentDraftImageView view = images.attach(owner, conversation.id(), "수채화 고양이");

        ChatAttachment copy = attachments.findById(Long.parseLong(view.assetId())).orElseThrow();
        assertThat(view.fileName()).isEqualTo(copy.storedName()).isEqualTo(copy.id() + ".png");
        assertThat(view.photoDir()).endsWith("/" + conversation.id());
        assertThat(view.title()).isEqualTo("수채화 고양이");
        assertThat(copy.uploadedByUserId()).isEqualTo(owner.id());
        assertThat(copy.messageId()).isNull();
        assertThat(copy.isDraftImageCopy()).isTrue();
        assertThat(attachmentService.read(owner, conversation.id(), copy.id()).body().readAllBytes())
            .as("승인 카드와 초안 미리보기는 대화의 첨부 주소로 그림을 연다")
            .containsExactly(png);

        assertThat(images.attach(owner, conversation.id(), "수채화 고양이").assetId())
            .as("원고를 고쳐 다시 넣어도 사본이 늘지 않는다")
            .isEqualTo(view.assetId());
        assertThat(attachments.findByConversationIdAndSourceArtifactIdOrderByIdDesc(conversation.id(), copy.sourceArtifactId()))
            .hasSize(1);

        var saved = drafts.create(owner, conversation.id(), draftWith(view.assetId()));
        assertThat(saved.draft().blocks().get(0).path("assetId").asString()).isEqualTo(view.assetId());
        assertThat(saved.expiresAt()).as("원고의 기한은 사본의 기한을 넘지 않는다").isBeforeOrEqualTo(copy.expiresAt());
    }


    @Test
    @DisplayName("사본은 사진 순번과 사진 참조 목록, 보내지 않은 사진 수, 메시지 묶기에 나오지 않는다")
    void copyStaysOutOfConversationPhotos() {
        generated.save(conversation.id(), owner.id(), "call-" + UUID.randomUUID(), "바다", "파란 바다", png(52));
        ContentDraftImageView view = images.attach(owner, conversation.id(), "바다");
        Long copyId = Long.parseLong(view.assetId());

        assertThat(attachmentService.agentInput(conversation.id(), List.of(), "글").text())
            .contains(photo.storedName())
            .doesNotContain(view.fileName());
        assertThat(attachments.countByConversationIdAndMessageIdIsNullAndDeletedAtIsNull(conversation.id())).isZero();
        assertThatThrownBy(() -> attachmentService.requireAttachable(conversation.id(), List.of(copyId)))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> attachmentService.attach(999L, conversation.id(), List.of(copyId)))
            .as("묶기 쿼리가 사본을 바꾸지 않는다")
            .isInstanceOf(ApiException.class);
        assertThat(attachments.findById(copyId).orElseThrow().messageId()).isNull();
    }


    @Test
    @DisplayName("보관 기간 정리로 파일이 지워진 그림은 넣지 못하고, 이미 넣은 사본은 원고에 남는다")
    void goneImageCanNotBeAddedButCopyStays() {
        generated.save(conversation.id(), owner.id(), "call-" + UUID.randomUUID(), "넣은 그림", "지시문", png(53));
        generated.save(conversation.id(), owner.id(), "call-" + UUID.randomUUID(), "안 넣은 그림", "지시문", png(54));
        ContentDraftImageView kept = images.attach(owner, conversation.id(), "넣은 그림");
        var saved = drafts.create(owner, conversation.id(), draftWith(kept.assetId()));
        jdbc.update("UPDATE chat_artifact SET deleted_at = CURRENT_TIMESTAMP WHERE conversation_id = ?", conversation.id());
        long copies = attachments.count();

        assertThatThrownBy(() -> images.attach(owner, conversation.id(), "안 넣은 그림"))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.IMAGE_SOURCE_GONE));
        assertThat(attachments.count()).as("사본을 만들지 않는다").isEqualTo(copies);
        assertThat(drafts.read(owner, conversation.id(), saved.draft().draftId(), null).draft().revision()).isEqualTo(1);
        assertThatThrownBy(() -> images.attach(owner, conversation.id(), "넣은 그림"))
            .as("사본이 있어도 지워진 그림은 새로 넣지 못한다")
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.IMAGE_SOURCE_GONE));
        var revised = drafts.revise(owner, conversation.id(), saved.draft().draftId(), 1, draftWith(kept.assetId()));
        assertThat(revised.draft().revision()).as("이미 넣은 원고는 사본 번호로 고쳐 저장한다").isEqualTo(2);
    }


    @Test
    @DisplayName("사본은 사진의 보관 기간이 지나면 원고에서 쓸 수 없고 그림이 있으면 새 사본을 만든다")
    void copyExpiresLikePhotos() {
        generated.save(conversation.id(), owner.id(), "call-" + UUID.randomUUID(), "하늘", "지시문", png(55));
        ContentDraftImageView first = images.attach(owner, conversation.id(), "하늘");
        clock.set(NOW.plus(Duration.ofDays(properties.retentionDays())).plusSeconds(1));

        assertThatThrownBy(() -> drafts.create(owner, conversation.id(), draftWith(first.assetId())))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ATTACHMENT_GONE));
        assertThat(images.attach(owner, conversation.id(), "하늘").assetId()).isNotEqualTo(first.assetId());
    }


    @Test
    @DisplayName("그 대화에 없는 제목과 다른 사용자의 대화는 넣지 못한다")
    void rejectsUnknownTitleAndForeignConversation() {
        generated.save(conversation.id(), owner.id(), "call-" + UUID.randomUUID(), "남의 그림", "지시문", png(56));

        assertThatThrownBy(() -> images.attach(owner, conversation.id(), "없는 제목"))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.IMAGE_SOURCE_UNKNOWN));
        assertThatThrownBy(() -> images.attach(user(), conversation.id(), "남의 그림"))
            .isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
    }


    private ContentDraftInput draftWith(String assetId) {
        var node = json.createObjectNode().put("title", "그림 글").put("category", "분류");
        node.putArray("tags");
        node.putArray("blocks").addObject().put("type", "image").put("assetId", assetId);
        return reader.read(node);
    }


    /** PNG 머리 바이트 뒤에 `filler` 값을 채운 테스트용 그림이다. */
    private static byte[] png(int filler) {
        byte[] bytes = new byte[128];
        Arrays.fill(bytes, (byte)filler);
        byte[] magic = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }
}
