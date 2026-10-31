package com.bifos.assistant.chat.artifact.application.dto;

import java.util.List;

/**
 * 보관하기 전에 훑은 묶음이다. 본문은 담지 않고 파일마다 크기와 SHA-256 만 담는다.
 *
 * @param conversationId 파일이 있는 대화의 내부 번호
 * @param root 사용자가 고른 파일
 * @param parts 뿌리가 부르는 파일들. 경로 순이다. 부르는 것이 없으면 비어 있다
 * @param fingerprint 묶음 전체의 지문. 부르는 파일이 없으면 PR 1 의 한 파일 지문과 같다
 */
public record ArtifactBundle(Long conversationId, ArtifactBundleFile root, List<ArtifactBundleFile> parts,
    String fingerprint) {

    /** 뿌리와 부르는 파일을 모두 더한 평문 바이트 수 */
    public long totalBytes() {
        return root.byteSize() + parts.stream().mapToLong(ArtifactBundleFile::byteSize).sum();
    }


    /** 뿌리를 센 파일 수 */
    public int fileCount() {
        return parts.size() + 1;
    }
}
