package com.bifos.assistant.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 클래스 의존에서 최상위 패키지 사이의 간선을 모은다.
 *
 * <p>최상위 패키지는 {@code com.bifos.assistant.<이름>} 의 {@code <이름>} 이다.
 * 패키지 루트에 바로 있는 클래스, {@code com.bifos.assistant} 밖의 클래스, {@code shared} 는 그래프에 넣지 않는다.
 * 배열은 원소 타입으로 본다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class TopLevelPackageEdges {

    private static final String ROOT_PACKAGE = "com.bifos.assistant";
    private static final String EXCLUDED_PACKAGE = "shared";

    /** 출발 패키지마다 도착 패키지. 이름 순이다. */
    private final Map<String, SortedSet<String>> edges = new TreeMap<>();

    /** 패키지마다 이름 순으로 첫 클래스. 그 패키지의 간선 위반은 이 클래스에서만 낸다. */
    private final Map<String, String> reportingClass = new TreeMap<>();

    static TopLevelPackageEdges of(Collection<JavaClass> classes) {
        TopLevelPackageEdges result = new TopLevelPackageEdges();
        for (JavaClass javaClass : classes) {
            Optional<String> source = topLevelPackageOf(javaClass);
            if (source.isEmpty()) {
                continue;
            }
            result.edges.computeIfAbsent(source.get(), ignored -> new TreeSet<>());
            result.reportingClass.merge(source.get(), javaClass.getName(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                topLevelPackageOf(dependency.getTargetClass().getBaseComponentType())
                        .filter(target -> !target.equals(source.get()))
                        .ifPresent(target -> {
                            result.edges.get(source.get()).add(target);
                            result.edges.computeIfAbsent(target, ignored -> new TreeSet<>());
                        });
            }
        }
        return result;
    }

    Map<String, SortedSet<String>> edges() {
        return Collections.unmodifiableMap(edges);
    }

    /** 그 클래스가 자기 최상위 패키지에서 이름 순으로 첫 클래스인지 돌려준다. */
    boolean reports(JavaClass item) {
        return topLevelPackageOf(item)
                .map(source -> item.getName().equals(reportingClass.get(source)))
                .orElse(false);
    }

    static Optional<String> topLevelPackageOf(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(ROOT_PACKAGE + ".")) {
            return Optional.empty();
        }
        String rest = packageName.substring(ROOT_PACKAGE.length() + 1);
        int dot = rest.indexOf('.');
        String topLevel = dot < 0 ? rest : rest.substring(0, dot);
        return EXCLUDED_PACKAGE.equals(topLevel) ? Optional.empty() : Optional.of(topLevel);
    }
}
