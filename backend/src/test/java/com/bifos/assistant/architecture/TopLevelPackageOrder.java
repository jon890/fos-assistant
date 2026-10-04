package com.bifos.assistant.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * 최상위 패키지 사이의 간선 가운데 층 순서를 거스르는 것을 위반으로 낸다.
 *
 * <p>{@link #ORDER} 는 아래에서 위로 적은 순서다. 위 패키지는 아래 패키지를 쓰고, 아래 패키지가 위 패키지를 쓰면 위반이다.
 * 순서에 없는 최상위 패키지도 위반이다. 새 패키지를 만들면 자리를 정해 {@link #ORDER} 에 넣는다.
 *
 * <p>간선은 {@link TopLevelPackageEdges} 가 모은다. 위반은 패키지 간선 하나에 하나이고,
 * 위반 문구가 기준 파일의 열쇠이므로 문구에 클래스 이름이나 줄 번호를 넣지 않는다.
 */
final class TopLevelPackageOrder extends ArchCondition<JavaClass> {

    /** 최상위 패키지의 층 순서. 아래에서 위로 적는다. */
    static final List<String> ORDER = List.of(
            "hermes",
            "user",
            "notification",
            "model",
            "agent",
            "skill",
            "usage",
            "memory",
            "context",
            "chat",
            "orchestration",
            "mcp",
            "people",
            "connector",
            "task");

    private TopLevelPackageEdges edges = TopLevelPackageEdges.of(List.of());

    TopLevelPackageOrder() {
        super("최상위 패키지가 층 순서의 아래쪽만 쓴다");
    }

    /**
     * 패키지 하나의 위반 문구를 이름 순으로 돌려준다.
     * 순서에 없는 도착 패키지는 그 패키지 쪽에서 내므로 여기서는 건너뛴다.
     */
    static List<String> violations(String source, Collection<String> targets) {
        int sourceIndex = ORDER.indexOf(source);
        if (sourceIndex < 0) {
            return List.of(source + " 는 층 순서에 없다");
        }
        List<String> violations = new ArrayList<>();
        for (String target : new TreeSet<>(targets)) {
            if (ORDER.indexOf(target) > sourceIndex) {
                violations.add(source + " -> " + target + " 는 층 순서를 거스른다");
            }
        }
        return violations;
    }

    @Override
    public void init(Collection<JavaClass> allObjectsToTest) {
        edges = TopLevelPackageEdges.of(allObjectsToTest);
    }

    @Override
    public void check(JavaClass item, ConditionEvents events) {
        if (!edges.reports(item)) {
            return;
        }
        String source = TopLevelPackageEdges.topLevelPackageOf(item).orElseThrow();
        for (String violation : violations(source, edges.edges().getOrDefault(source, new TreeSet<>()))) {
            events.add(SimpleConditionEvent.violated(item, violation));
        }
    }
}
