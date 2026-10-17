package com.bifos.assistant.chat.contentdraft.application.port;

import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftJobPage;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftJobView;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.UUID;

/** 원래 대화의 주인에게 Control Plane이 보관한 원고 저장 job만 보인다. 클라이언트 jobId는 받지 않는다. */
public interface ContentDraftJobQueries {
    ContentDraftJobView refresh(CurrentUser user, Long conversationId, UUID draftId, UUID snapshotId);


    /** @param beforeSnapshotId 이전 페이지 마지막 snapshot의 공개 ID. 첫 페이지는 null */
    ContentDraftJobPage list(CurrentUser user, Long conversationId, UUID beforeSnapshotId, int limit);
}
