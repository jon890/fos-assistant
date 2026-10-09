package com.bifos.assistant.workspace.application.model;

import java.io.InputStream;

/**
 * 응답으로 줄 본문이다. 스트림은 응답이 닫는다.
 *
 * @param name 마지막 조각. {@code Content-Disposition} 의 이름이다
 * @param html HTML 미리보기다. 결과물과 같은 {@code Content-Security-Policy} 를 붙인다
 * @param download 내려받기다
 */
public record WorkspaceFile(
        String name, String contentType, long size, boolean html, boolean download, InputStream body) {}
