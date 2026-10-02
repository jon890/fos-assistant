package com.bifos.assistant.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 최상위 패키지 사이의 간선 가운데 순환에 속한 것을 위반으로 낸다.
 *
 * <p>최상위 패키지는 {@code com.bifos.assistant.<이름>} 의 {@code <이름>} 이다.
 * 패키지 루트에 바로 있는 클래스, {@code com.bifos.assistant} 밖의 클래스, {@code shared} 는 그래프에 넣지 않는다.
 * {@code shared} 는 모든 도메인이 쓰는 기반 패키지로 보고, {@code shared} 에서 나가는 의존은
 * {@code ArchitectureRules.SHARED_DOES_NOT_DEPEND_ON_DOMAINS} 가 따로 막는다.
 *
 * <p>위반은 패키지 간선 하나에 하나다. {@code B} 에서 {@code A} 로 닿을 수 있을 때 간선 {@code A -> B} 가 위반이다.
 * 클래스 의존 하나하나를 위반으로 얼리면, 지금 패키지가 모두 한 순환 묶음이라 패키지를 넘는 새 의존이 모두 실패한다.
 * 간선 단위로 얼리면 이미 있는 간선 위에 의존을 더하는 것은 통과하고 순환을 늘리는 새 간선만 실패한다.
 *
 * <p>ArchUnit 의 {@code slices().should().beFreeOfCycles()} 를 쓰지 않는다.
 * 기본 설정은 순환을 100개까지만 찾으므로, 이 저장소처럼 촘촘한 그래프에서는 어느 순환이 먼저 잡히는지에 따라
 * 얼린 목록이 달라질 수 있다. 이 조건은 같은 입력에 같은 위반 문구를 같은 순서로 낸다.
 * 위반 문구가 기준 파일의 열쇠이므로 문구에 클래스 이름이나 줄 번호를 넣지 않는다.
 */
final class TopLevelPackageCycles extends ArchCondition<JavaClass> {

    /** 출발 패키지마다 순환에 속한 간선의 도착 패키지. 이름 순이다. */
    private final Map<String, SortedSet<String>> cyclicEdges = new TreeMap<>();

    private TopLevelPackageEdges packageEdges = TopLevelPackageEdges.of(List.of());

    TopLevelPackageCycles() {
        super("최상위 패키지 사이의 간선이 순환에 속하지 않는다");
    }

    @Override
    public void init(Collection<JavaClass> allObjectsToTest) {
        cyclicEdges.clear();
        packageEdges = TopLevelPackageEdges.of(allObjectsToTest);
        Map<String, SortedSet<String>> edges = packageEdges.edges();

        List<String> nodes = new ArrayList<>(edges.keySet());
        boolean[][] reach = reachability(nodes, edges);
        edges.forEach((source, targets) -> targets.forEach(target -> {
            if (reach[nodes.indexOf(target)][nodes.indexOf(source)]) {
                cyclicEdges.computeIfAbsent(source, ignored -> new TreeSet<>()).add(target);
            }
        }));
    }

    @Override
    public void check(JavaClass item, ConditionEvents events) {
        if (!packageEdges.reports(item)) {
            return;
        }
        String source = TopLevelPackageEdges.topLevelPackageOf(item).orElseThrow();
        for (String target : cyclicEdges.getOrDefault(source, new TreeSet<>())) {
            events.add(SimpleConditionEvent.violated(item, source + " -> " + target + " 는 순환에 속한다"));
        }
    }

    /** Floyd–Warshall 로 패키지마다 닿을 수 있는 패키지를 구한다. 노드가 열 개 남짓이라 충분하다. */
    private static boolean[][] reachability(List<String> nodes, Map<String, SortedSet<String>> edges) {
        int size = nodes.size();
        boolean[][] reach = new boolean[size][size];
        edges.forEach((source, targets) ->
                targets.forEach(target -> reach[nodes.indexOf(source)][nodes.indexOf(target)] = true));
        for (int via = 0; via < size; via++) {
            for (int from = 0; from < size; from++) {
                if (!reach[from][via]) {
                    continue;
                }
                for (int to = 0; to < size; to++) {
                    if (reach[via][to]) {
                        reach[from][to] = true;
                    }
                }
            }
        }
        return reach;
    }
}
