package com.bifos.assistant.hermes.dto;

/**
 * 그 profile 의 {@code SOUL.md} 를 읽은 것이다.
 *
 * <p>대시보드에서 그 profile 의 성격을 읽는 응답이다. 파일이 없으면 대시보드가 {@code content} 를
 * 빈 문자열로 두고 {@code exists} 를 거짓으로 돌려준다. 어느 경로를 부르는지는 {@code
 * HttpHermesDashboardClient} 가 갖는다.
 *
 * @param content 본문. 파일이 없으면 빈 문자열
 * @param exists 그 파일이 있었는가
 */
public record SoulDocument(String content, boolean exists) {

    public SoulDocument {
        content = content == null ? "" : content;
    }
}
