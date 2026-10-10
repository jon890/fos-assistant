package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.MediaObservationPages;
import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.MediaObservationView;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MediaObservationPagesTest extends ObservationFixture {
    @Autowired
    MediaObservationPages pages;

    @Test
    @DisplayName("본문 예산 커서를 끝까지 따라가도 보낸 첨부가 중복되거나 빠지지 않는다")
    void followsByteBudgetWithoutSkippingOrDuplicatingSentIds() {
        var expected = new ArrayList<String>();
        for (int index = 0; index < 8; index++) {
            var attachment = index == 0 ? photo : photo();
            expected.add(attachment.id().toString());
            var claims = new ArrayList<MediaObservationInput.Claim>();
            for (int count = 0; count < index % 3 + 5; count++) {
                claims.add(new MediaObservationInput.Claim("OCR", "😀\\\"".repeat(120), "UNCERTAIN", List.of("합성 근거")));
            }
            var body = new MediaObservationInput(
                    input().status(), "한글 😀", claims, List.of(), input().coverage(), input().evidence(), null);
            service.record(owner, conversation.id(), attachment.id(), 0, UUID.randomUUID(), body, model());
        }
        attachmentCleaner.delete(photo, NOW);
        var actual = new ArrayList<String>();
        String cursor = null;
        int pageCount = 0;
        do {
            var page = pages.list(owner, conversation.id(), cursor, 3);
            assertThat(page.items()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
            assertThat(page.items().stream()
                            .filter(item -> item.observation() != null)
                            .mapToInt(item -> json.writeValueAsBytes(item.observation()).length)
                            .sum())
                    .isLessThanOrEqualTo(32768);
            actual.addAll(
                    page.items().stream().map(MediaObservationView::assetId).toList());
            pageCount++;
            cursor = page.nextAfterAssetId();
            if (cursor != null) {
                assertThat(cursor).isEqualTo(page.items().getLast().assetId());
            }
            assertThat(pageCount).isLessThanOrEqualTo(expected.size());
        } while (cursor != null);
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(pages.list(owner, conversation.id(), null, 30)
                        .items()
                        .getFirst()
                        .ordinal())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("페이지 한도와 빈 끝을 지키며 조회는 행을 만들지 않는다")
    void enforcesLimitAndEmptyEndWithoutCreatingRows() {
        code(() -> pages.list(owner, conversation.id(), null, 31), ErrorCode.VALIDATION_FAILED);
        assertThat(pages.list(owner, conversation.id(), photo.id().toString(), 1)
                        .items())
                .isEmpty();
        assertThat(pages.list(owner, conversation.id(), null, 1).nextAfterAssetId())
                .isNull();
        assertThat(observations.count()).isZero();
    }
}
