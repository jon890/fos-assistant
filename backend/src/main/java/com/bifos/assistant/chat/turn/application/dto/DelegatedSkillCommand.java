package com.bifos.assistant.chat.turn.application.dto;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 메시지나 예약 작업 지시 맨 앞의 {@code /<에이전트 이름> · <스킬>} 이다. 그 에이전트에게 그 스킬로 맡긴다(docs/features/assistant-hub/spec.md 의
 * REQ10, DECISION27).
 *
 * <p>비서의 {@code /이름}({@link SkillCommand})을 넓힌 꼴이다. 에이전트 이름 뒤에 공백, 가운뎃점, 공백이 오고 스킬 이름 뒤에는 공백이나 끝이
 * 온다. 이름에는 줄바꿈과 가운뎃점이 없다. {@code /abc · x} 를 비서의 {@code abc} 스킬로 읽지 않게 {@link SkillCommand} 보다 먼저 본다.
 *
 * @param agentName 에이전트 이름. 앞뒤 공백을 뺀 것이다
 * @param command 그 에이전트에서 부를 스킬과 나머지 글
 */
public record DelegatedSkillCommand(String agentName, SkillCommand command) {

    /** 이름과 스킬 사이의 글이다. 목록이 보이는 「에이전트 · 스킬」 과 같다. */
    public static final String SEPARATOR = " · ";

    private static final Pattern FORM = Pattern.compile("^/([^\\n·]+?) · ([a-z0-9][a-z0-9-]{0,63})(\\s|$)");


    /** 그 에이전트 이름을 이 꼴로 적으면 그대로 다시 읽히는지 본다. 가운뎃점이나 줄바꿈이 있거나 앞뒤에 공백이 있으면 읽히지 않는다. */
    public static boolean readsBack(String agentName) {
        return agentName != null && !agentName.isBlank() && agentName.strip().equals(agentName) && agentName.indexOf('·') < 0
            && agentName.indexOf('\n') < 0;
    }


    /** 글이 이 꼴이면 에이전트 이름과 스킬, 나머지 글을 낸다. 아니면 빈 값이다. */
    public static Optional<DelegatedSkillCommand> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = FORM.matcher(text);
        if (!matcher.lookingAt()) {
            return Optional.empty();
        }
        String agentName = matcher.group(1).strip();
        if (agentName.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DelegatedSkillCommand(
            agentName, new SkillCommand(matcher.group(2), text.substring(matcher.end()).strip())));
    }
}
