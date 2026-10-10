package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.MediaObservationPage;
import com.bifos.assistant.chat.application.model.MediaObservationView;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 저장 서비스의 권한·원본 검사를 유지하면서 공개 페이지의 본문 합계를 제한한다. */
@Service
@RequiredArgsConstructor
public class MediaObservationPages {
    private final MediaObservationService observations;
    private final ObjectMapper json;

    public MediaObservationPage list(CurrentUser user, Long conversationId, String afterAssetId, int limit) {
        if (limit < 1 || limit > 30) {
            throw MediaObservationInputReader.invalid();
        }
        var candidates = observations.list(user, conversationId, afterAssetId, limit + 1);
        var items = new ArrayList<MediaObservationView>();
        int bytes = 0;
        for (MediaObservationView item : candidates) {
            int size = item.observation() == null ? 0 : json.writeValueAsBytes(item.observation()).length;
            if (items.size() == limit || bytes + size > MediaObservationInput.MAX_BODY_BYTES) {
                break;
            }
            items.add(item);
            bytes += size;
        }
        String next = items.size() < candidates.size() ? items.getLast().assetId() : null;
        return new MediaObservationPage(items, next);
    }
}
