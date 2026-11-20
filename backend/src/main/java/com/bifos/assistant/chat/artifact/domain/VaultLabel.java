package com.bifos.assistant.chat.artifact.domain;

import java.util.Optional;

/**
 * 「내 파일」 의 파일 하나에 붙는 제목과 설명이다(스펙 workspace REQ6). 앞뒤 공백을 떼고 길이를 자른 값만 담는다.
 *
 * @param title 제목. 없으면 null
 * @param description 한 줄 설명. 없으면 null
 */
public record VaultLabel(String title, String description) {

    public static final int MAX_TITLE_CHARS = 200;
    public static final int MAX_DESCRIPTION_CHARS = 500;


    /** 받은 글을 다듬는다. 둘 다 비면 빈 값이다. */
    public static Optional<VaultLabel> of(String title, String description) {
        String cleanTitle = clean(title, MAX_TITLE_CHARS);
        String cleanDescription = clean(description, MAX_DESCRIPTION_CHARS);
        return cleanTitle == null && cleanDescription == null
            ? Optional.empty()
            : Optional.of(new VaultLabel(cleanTitle, cleanDescription));
    }


    public VaultLabel withTitle(String newTitle) {
        return new VaultLabel(newTitle, description);
    }


    private static String clean(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String oneLine = value.replaceAll("\\s+", " ").strip();
        if (oneLine.isEmpty()) {
            return null;
        }
        int end = oneLine.offsetByCodePoints(0, Math.min(maxChars, oneLine.codePointCount(0, oneLine.length())));
        return oneLine.substring(0, end).strip();
    }
}
