package com.bifos.assistant.chat.application;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 메시지 맨 앞의 {@code /이름} 으로 부른 스킬 커맨드다. 근거는 ADR-035 에 있다.
 *
 * <p>이름 뒤에 공백이나 끝이 올 때만 커맨드다. {@code /usr/bin} 처럼 이름 뒤에 곧바로 다른 글자가 오면
 * 커맨드가 아니다. 이름 규칙은 올린 스킬의 이름 규칙과 같다.
 *
 * @param name 스킬 이름
 * @param rest 이름 뒤의 글. 이름 뒤 공백 하나를 빼고 앞뒤 공백도 뺀 것이다. 없으면 빈 글이다
 */
public record SkillCommand(String name, String rest) {

    private static final Pattern COMMAND = Pattern.compile("^/([a-z0-9][a-z0-9-]{0,63})(\\s|$)");

    /** 메시지가 커맨드 모양이면 그 이름과 나머지 글을 낸다. 아니면 빈 값이다. */
    public static Optional<SkillCommand> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = COMMAND.matcher(text);
        if (!matcher.lookingAt()) {
            return Optional.empty();
        }
        return Optional.of(new SkillCommand(matcher.group(1), text.substring(matcher.end()).strip()));
    }

    /**
     * Hermes 에 보낼 입력이다. API server 는 입력의 {@code /} 를 해석하지 않으므로 모델이 {@code skill_view} 로
     * 스킬을 읽게 문장으로 바꾼다. 나머지 글이 없으면 스킬의 절차를 처음부터 진행하게 한다.
     */
    public String hermesInput() {
        String invoked = "사용자가 `" + name + "` 스킬을 호출했다. `skill_view(name=\"" + name + "\")` 로 스킬을 읽고 ";
        if (rest.isEmpty()) {
            return invoked + "스킬의 절차를 처음부터 진행한다.";
        }
        return invoked + "그 절차대로 다음을 한다: " + rest;
    }
}
