package com.bifos.assistant.architecture;

import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 하위 기능으로 나눈 기능과 그 순서다. 순서는 아래에서 위로 적는다.
 *
 * <p>위 하위 기능은 아래 하위 기능을 쓰고, 아래가 위를 쓰면 {@code SUB_FEATURES_FOLLOW_ORDER} 가 위반으로 낸다.
 * 이 목록에 있는 기능은 루트에 역할 패키지를 두지 않는다({@code SPLIT_FEATURES_HAVE_NO_ROOT_ROLES}).
 * 값은 ADR-20261015 / package-layout 의 「하위 기능」 표와 같다. 새 하위 기능은 ADR 에서 정하고 여기에 자리를 넣는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SubFeatureOrder {

    /** 기능마다 하위 기능의 순서. 아래에서 위로 적는다. */
    static final Map<String, List<String>> ORDER = Map.of(
        "chat", List.of(
            "conversation", "delivery", "modelchoice", "attachment", "artifact", "contentdraft",
            "starter", "turn", "nextturn", "recovery", "purge"),
        "connector", List.of("connection", "action", "financial", "contentdraft", "approval"),
        "proactive", List.of("check", "evaluation", "autonomy", "loop", "surface", "feedback"),
        "usage", List.of("pricing", "execution", "subagent", "tree", "summary"),
        "skill", List.of("usage", "library", "upload"));
}
