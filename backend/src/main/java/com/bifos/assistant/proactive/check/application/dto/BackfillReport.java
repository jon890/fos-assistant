package com.bifos.assistant.proactive.check.application.dto;

/**
 * 옛 발견과 문제 후보를 제안 줄로 옮긴 결과의 줄 수다.
 *
 * @param created 새로 만든 제안 줄
 * @param existing 이전 기동이 이미 옮겨 둔 줄
 * @param skipped 같은 제목의 열린 할 일이 있거나 거절한 제목이거나 제목이 비어 옮기지 않은 줄
 * @param failed 저장하지 못해 다음 기동으로 미룬 줄
 * @param repointed 새 제안 줄의 열쇠로 옮긴 반응 사건
 */
public record BackfillReport(int created, int existing, int skipped, int failed, int repointed) {


    public static final BackfillReport EMPTY = new BackfillReport(0, 0, 0, 0, 0);

    public BackfillReport plus(BackfillReport other) {
        return new BackfillReport(
            created + other.created,
            existing + other.existing,
            skipped + other.skipped,
            failed + other.failed,
            repointed + other.repointed);
    }
}
