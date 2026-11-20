package com.bifos.assistant.chat.artifact.domain;

import java.util.Locale;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 「내 파일」 의 파일을 내려받을 때의 이름이다(스펙 workspace REQ14). 제목이 있으면 제목에 원래 확장자를 붙인다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class VaultDownloadName {

    /**
     * 내려받을 이름이다. 제목이 없으면 파일 이름 그대로다.
     *
     * @param title 제목. 없으면 null
     * @param fileName 보관할 때의 파일 이름
     * @param extension 붙일 확장자. 점은 빼고 준다. null 이면 파일 이름의 확장자다
     */
    public static String of(String title, String fileName, String extension) {
        String safe = title == null ? "" : title.replaceAll("[/\\\\:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (safe.isEmpty() || safe.chars().allMatch(c -> c == '.' || c == '_')) {
            return fileName;
        }
        String suffix = extension == null ? extensionOf(fileName) : extension;
        if (suffix.isEmpty() || safe.toLowerCase(Locale.ROOT).endsWith("." + suffix.toLowerCase(Locale.ROOT))) {
            return safe;
        }
        return safe + "." + suffix;
    }


    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1) : "";
    }
}
