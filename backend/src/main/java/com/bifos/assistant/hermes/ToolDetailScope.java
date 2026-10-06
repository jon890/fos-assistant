package com.bifos.assistant.hermes;

import java.util.Set;

/**
 * 실행 기록에서 어느 도구의 내용을 통째로 가릴지 정한다.
 *
 * <p>커넥터 도구의 인자와 결과에는 외부 서비스의 글이 실린다. 그 글이 실행 기록에 남아 관리자에게 보이지 않게 한다. 옛 커넥터
 * 에이전트는 모든 도구를 가리고, 다른 에이전트는 붙은 커넥터 서버의 도구만 가린다(ADR-083).
 *
 * @param hideAll 모든 도구의 내용을 가린다. 옛 커넥터 에이전트의 실행이다
 * @param hiddenPrefixes 내용을 가릴 도구의 등록 이름 앞부분
 */
public record ToolDetailScope(boolean hideAll, Set<String> hiddenPrefixes) {
    /** 아무 도구도 통째로 가리지 않는다. 비밀값과 식별자는 여전히 가린다. */
    public static final ToolDetailScope NONE = new ToolDetailScope(false, Set.of());

    /** 모든 도구를 가린다. */
    public static final ToolDetailScope ALL = new ToolDetailScope(true, Set.of());

    public ToolDetailScope {
        hiddenPrefixes = Set.copyOf(hiddenPrefixes);
    }

    /** 그 붙은 커넥터 서버들의 도구만 가린다. */
    public static ToolDetailScope prefixes(Set<String> hiddenPrefixes) {
        return new ToolDetailScope(false, hiddenPrefixes);
    }

    /** 그 도구의 내용을 통째로 가리는가. 이름을 모르는 도구는 모두 가릴 때만 가린다. */
    public boolean hides(String toolName) {
        if (hideAll) {
            return true;
        }
        return toolName != null && hiddenPrefixes.stream().anyMatch(toolName::startsWith);
    }
}
