package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.ContentDraftBindingStamp;
import com.bifos.assistant.shared.auth.CurrentUser;

/** HTTP 선언 준비와 잠금 안의 DB 권한 확인을 분리한다. */
public interface ContentDraftBindingAccess {
    ContentDraftBindingStamp prepare(CurrentUser user, Long agentId, Long bindingId);


    ContentDraftBindingStamp requireCurrent(CurrentUser user, Long agentId, Long bindingId);


    void requireUnchanged(CurrentUser user, ContentDraftBindingStamp stamp);
}
