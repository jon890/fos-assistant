package com.bifos.assistant.agent.domain;

import com.bifos.assistant.agent.domain.type.AgentColor;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;

/**
 * 에이전트의 이름과 소개, 아이콘, 색이다(docs/features/agent-team/spec.md 의 REQ1).
 *
 * <p>판정은 {@link #of} 한 곳에서 한다. 앞뒤 공백을 떼고, 빈 소개와 아이콘은 null 로 둔다. 이름은 비울 수 없다.
 *
 * @param name 보이는 이름. 1자부터 {@link #MAX_NAME_CHARS} 자까지
 * @param description 한 줄 소개. 없으면 null
 * @param icon {@link #ICONS} 가운데 하나. 없으면 null
 * @param color 없으면 null
 */
public record AgentIntroduction(String name, String description, String icon, AgentColor color) {


    /** {@code agent.name} 칸의 길이와 같다. */
    public static final int MAX_NAME_CHARS = 100;

    /**
     * 소개의 상한이다. {@code agent.description} 칸의 길이와 같다. 휴대폰 카드 한 줄에 들어가고, 위임 도구 {@code agent_list}
     * 로 매 위임 판단에 실린다(docs/features/agent-team/design.md 의 DECISION3).
     */
    public static final int MAX_DESCRIPTION_CHARS = 60;

    /** 기본 비서의 이름이다. 첫 로그인에 만드는 에이전트가 이 이름을 쓴다(spec 의 DECISION5). */
    public static final String PRIMARY_NAME = "비서";

    /** 기본 비서의 소개다. 마이그레이션 {@code V20261105074407} 이 같은 글을 쓴다. */
    public static final String PRIMARY_DESCRIPTION = "무엇이든 먼저 물어보세요. 필요하면 전문 에이전트에게 맡겨요";

    /**
     * 고를 수 있는 아이콘이다. 화면의 {@code web/src/lib/agent-badge.ts} 가 같은 목록을 같은 순서로 갖는다.
     *
     * <p>아무 글자나 받지 않는 것은 이모지 하나인지 서버가 판정하기 어렵기 때문이다(design 의 DECISION4).
     */
    public static final List<String> ICONS = List.of(
        "🤖", "🛍️", "🚆", "✉️", "📅", "💰", "✍️", "📚",
        "🏠", "🍳", "💪", "✈️", "🎵", "📷", "💻", "⭐",
        "🌱", "🧾", "🗺️", "🧸", "🩺", "🎮", "📝", "🔎");

    public static AgentIntroduction of(String name, String description, String icon, AgentColor color) {
        String stripped = blankToNull(description);
        if (stripped != null && stripped.length() > MAX_DESCRIPTION_CHARS) {
            throw new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "an agent description must be at most " + MAX_DESCRIPTION_CHARS + " characters");
        }
        String chosenIcon = blankToNull(icon);
        if (chosenIcon != null && !ICONS.contains(chosenIcon)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown agent icon");
        }
        return new AgentIntroduction(requireName(name), stripped, chosenIcon, color);
    }


    /** 만들기와 고치기가 같은 이름 판정을 쓴다. 공백을 뗀 이름을 돌려준다. */
    public static String requireName(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_NAME_CHARS) {
            throw new ApiException(
                ErrorCode.VALIDATION_FAILED, "an agent name must be 1 to " + MAX_NAME_CHARS + " characters");
        }
        return stripped;
    }


    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
