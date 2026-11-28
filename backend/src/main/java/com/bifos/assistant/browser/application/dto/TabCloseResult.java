package com.bifos.assistant.browser.application.dto;

/**
 * 탭 닫기의 결과다. 제목과 주소를 담지 않는다(docs/features/agent-browser/spec.md 의 REQ8).
 *
 * @param closed 닫은 탭 수
 * @param skipped 없어졌거나, 다른 페이지로 갔거나, 다른 작업이 쥐고 있어 건너뛴 탭 수
 */
public record TabCloseResult(int closed, int skipped) {
}
