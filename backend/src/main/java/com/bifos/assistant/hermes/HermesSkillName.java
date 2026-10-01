package com.bifos.assistant.hermes;

import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Hermes 가 받는 스킬 이름 규칙이다. 올린 스킬뿐 아니라 Hermes 가 가진 스킬의 이름도 여기에 맞는다.
 *
 * <p>Hermes 는 소문자, 숫자, 점, 밑줄, 붙임표로 64자까지 받는다. 첫 글자를 영문 소문자나 숫자로 묶어
 * {@code .} 과 {@code ..} 같은 이름을 막는다. 화면(web/src/lib/skill.ts)의 같은 규칙과 함께 고친다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HermesSkillName {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /** {@code skill_view} 미리보기에서 이름과 참고 파일 경로를 나누는 글자다. */
    private static final String PREVIEW_PATH_SEPARATOR = "→";

    /** Hermes 가 미리보기를 길이 상한에서 자를 때 끝에 붙이는 표시다. */
    private static final String TRUNCATION_MARK = "...";

    /** 이름이 규칙에 맞는지 본다. {@code null} 은 맞지 않는다. */
    public static boolean isValid(String name) {
        return name != null && PATTERN.matcher(name).matches();
    }

    /**
     * {@code skill_view} 미리보기에서 이름만 꺼낸다.
     *
     * <p>Hermes 는 미리보기가 길이 상한을 넘으면 앞부분만 남기고 끝에 {@code ...} 을 붙인다. 이름 규칙이 점을
     * 받으므로 이름 중간에서 잘린 값도 규칙에는 맞는다. 그대로 받으면 없는 스킬 이름이 사용 기록에 남으므로,
     * 이름 부분이 {@code ...} 로 끝나면 잘린 것으로 보고 버린다. 파일 경로 쪽만 잘린 미리보기는 이름이
     * 온전하므로 받는다. 이 판정은 미리보기를 읽는 여기에만 두고 {@link #isValid} 의 규칙에는 넣지 않는다.
     *
     * @param preview 스킬 이름이거나 {@code 이름 → 파일 경로} 다
     * @return 규칙에 맞고 잘리지 않은 이름. 그렇지 않으면 {@code null} 이다
     */
    public static String fromPreview(String preview) {
        if (preview == null) {
            return null;
        }
        int separator = preview.indexOf(PREVIEW_PATH_SEPARATOR);
        String name = (separator < 0 ? preview : preview.substring(0, separator)).strip();
        return isValid(name) && !name.endsWith(TRUNCATION_MARK) ? name : null;
    }
}
