package com.bifos.assistant.workspace.application;

import com.bifos.assistant.workspace.application.model.WorkspacePreview;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 이름의 확장자로 미리보기 형식과 크기 상한을 정한다. 파일 내용으로 형식을 짐작하지 않는다.
 *
 * <p>표와 확장자 규칙은 {@code docs/code-architecture.md} 의 「본문 머리글」 이 갖는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WorkspacePreviewPolicy {

    private static final long MIB = 1024L * 1024L;
    private static final WorkspacePreview TEXT = new WorkspacePreview("text/plain; charset=utf-8", MIB, false);
    private static final Map<String, WorkspacePreview> BY_EXTENSION = byExtension();

    /** 미리보기를 정하지 않은 확장자면 비어 있다. */
    public static Optional<WorkspacePreview> of(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return Optional.of(TEXT);
        }
        // 맨 앞의 . 하나뿐인 이름(.env)은 그 뒤 전체가 확장자다. lastIndexOf 가 0 이면 그렇게 읽힌다.
        return Optional.ofNullable(BY_EXTENSION.get(name.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }

    private static Map<String, WorkspacePreview> byExtension() {
        Map<String, WorkspacePreview> map = new HashMap<>();
        WorkspacePreview html = new WorkspacePreview("text/html; charset=utf-8", 5 * MIB, true);
        map.put("html", html);
        map.put("htm", html);
        long imageMax = 20 * MIB;
        map.put("png", new WorkspacePreview("image/png", imageMax, false));
        map.put("jpg", new WorkspacePreview("image/jpeg", imageMax, false));
        map.put("jpeg", new WorkspacePreview("image/jpeg", imageMax, false));
        map.put("gif", new WorkspacePreview("image/gif", imageMax, false));
        map.put("webp", new WorkspacePreview("image/webp", imageMax, false));
        map.put("css", new WorkspacePreview("text/css; charset=utf-8", MIB, false));
        // SVG 는 스크립트를 품을 수 있어 사진이 아니라 글로 준다.
        String texts = "csv tsv txt md markdown log json jsonl yaml yml toml ini cfg conf env py js mjs cjs ts tsx jsx "
                + "java kt go rs rb sh bash zsh sql xml svg scss";
        for (String extension : texts.split(" ")) {
            map.put(extension, TEXT);
        }
        return Map.copyOf(map);
    }
}
