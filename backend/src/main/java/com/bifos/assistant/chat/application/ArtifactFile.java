package com.bifos.assistant.chat.application;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 돌려줄 결과물 파일 하나를 열기 전의 판정 결과다. 파일을 열지 않았으므로 닫을 것이 없다.
 *
 * @param conversationId 파일이 있는 대화의 내부 번호
 * @param relativePath 대화 폴더 기준의 요청 경로
 * @param path 링크를 따라간 실제 파일 경로
 * @param contentType 실제 파일의 확장자로 정한 형식
 * @param byteSize 본문의 바이트 수
 * @param lastModified 파일의 수정 시각
 */
public record ArtifactFile(
        Long conversationId,
        String relativePath,
        Path path,
        String contentType,
        long byteSize,
        Instant lastModified) {

    /**
     * 조건부 요청에 쓰는 약한 검증자다.
     *
     * <p>바이트 수와 수정 시각으로 만든 값이라 본문이 같다고 보장하지 않는다. 그래서 {@code W/} 를 붙인다.
     */
    public String etag() {
        return "W/\"" + Long.toHexString(byteSize) + "-" + Long.toHexString(lastModified.toEpochMilli()) + "\"";
    }
}
