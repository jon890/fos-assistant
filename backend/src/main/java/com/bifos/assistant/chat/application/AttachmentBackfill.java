package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** 기동할 때 옛 첨부를 사용자별 디렉터리에 복사한다. 실패하면 준비 완료로 알리지 않는다(ADR-090). */
@Component
@RequiredArgsConstructor
@Slf4j
public class AttachmentBackfill implements ApplicationRunner {

    private static final int PAGE_SIZE = 256;

    private final ChatAttachmentRepository attachments;
    private final AttachmentStore store;

    @Override
    public void run(ApplicationArguments args) {
        copyLegacyFiles();
    }

    /** 파일이 없는 행은 그대로 두고, 삭제하지 않은 행만 멱등 복사한다. DB 내용은 바꾸지 않는다. */
    public void copyLegacyFiles() {
        int pageNumber = 0;
        Page<ChatAttachment> page;
        do {
            page = attachments.findByDeletedAtIsNullAndStoredNameIsNotNull(
                    PageRequest.of(pageNumber, PAGE_SIZE, Sort.by("id")));
            for (ChatAttachment attachment : page) {
                store.prepare(attachment);
            }
            pageNumber++;
        } while (page.hasNext());
        log.info("attachment user-directory backfill completed pages={}", pageNumber);
    }
}
