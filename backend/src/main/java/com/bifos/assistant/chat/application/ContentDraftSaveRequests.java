package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.ContentDraftSaveRequestView;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.UUID;

/** 승인한 snapshot의 저장 요청을 만든다. 구현은 커넥터가 갖고 chat은 커넥터를 import하지 않는다. */
public interface ContentDraftSaveRequests {
    /**
     * 같은 snapshot의 기존 승인 줄이 있으면 그 줄을 그대로 돌려주고 새 승인·job을 만들지 않는다.
     *
     * @param originExecutionId 호출자가 검증한 현재 실행 번호
     */
    ContentDraftSaveRequestView requestSave(CurrentUser user, Long originExecutionId, UUID snapshotId);
}
