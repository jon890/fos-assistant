package com.bifos.assistant.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 패키지 배치 규칙의 판정과 실패 메시지다. 까닭과 예외는 docs/code-rules 의 번호 문서가 갖는다.
 *
 * <p>구조는 {@code <기능>[.<하위 기능>].<역할>} 이고, 결정과 근거는 ADR-20261015 / package-layout 이 갖는다.
 * 규칙마다 root 를 받아 운영 코드와 fixture 가 같은 판정을 쓴다.
 *
 * <p>메시지에는 개수와 줄 번호를 넣지 않는다. 기준 파일의 줄이 흔들리기 때문이다.
 * 이동 뒤에도 남는 위반의 메시지는 위반한 클래스의 위치(패키지)를 담지 않는다. 위치가 바뀌어도 같은 줄로 견주기 위해서다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class PackageLayoutRules {

    /** 운영 코드의 패키지 루트다. */
    static final String PRODUCTION_ROOT = "com.bifos.assistant";

    /** application 직하 클래스 수의 상한이다. */
    static final int APPLICATION_SIZE_LIMIT = 25;

    private static final Set<String> ROLE_PACKAGES = Set.of("application", "domain", "infra", "presentation", "config");
    private static final String ADMIN = "admin";
    private static final String CURRENT_USER_PROVIDER = ".shared.auth.CurrentUserProvider";

    /** 이동이 끝나기 전의 옛 이름이다. admin 이 쓸 수 있는 것으로 임시 허용하고 마지막 이동 PR 이 지운다. */
    private static final Set<String> TRANSITIONAL_APPLICATION_PACKAGES = Set.of("application.model", "application.toolset");


    /** rule-70: 빈이 아닌 타입은 application 직하에 두지 않는다. */
    static ArchRule applicationRootHoldsOnlyBeans(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("application 직하에 빈이 아닌 타입을 두지 않는다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (item.getEnclosingClass().isPresent()
                        || !item.getPackageName().endsWith(".application")
                        || item.isAnnotatedWith(ConfigurationProperties.class)) {
                        return;
                    }
                    String name = item.getName();
                    if (item.isInterface()) {
                        report(item, events, name + " 는 인터페이스다. application 직하에는 빈만 둔다. "
                            + "구현을 바깥(infra 나 위 기능의 application)에 두는 인터페이스는 "
                            + destination(root, item, "application.port") + " 로 옮긴다.");
                    } else if (item.isRecord() || item.isEnum()) {
                        report(item, events, name + " 는 값이다. application 직하에는 빈만 둔다. "
                            + "서비스가 주고받는 값은 " + destination(root, item, "application.dto") + " 로 옮긴다.");
                    } else if (item.getModifiers().contains(JavaModifier.PUBLIC) && !isBean(item)) {
                        report(item, events, name + " 는 public 클래스인데 Spring 빈이 아니다. 빈으로 만들거나, "
                            + "같은 패키지에서만 쓰면 package-private 으로 낮추거나, 정책이나 값 함수면 domain 으로 옮긴다.");
                    }
                }
            })
            .as("application 직하에는 빈만 둔다");
    }


    /** rule-71: application 아래 하위 패키지는 dto 와 port 뿐이다. */
    static ArchRule applicationSubpackagesAreDtoAndPort(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new PackageCollector("application 아래에 dto 와 port 가 아닌 하위 패키지를 두지 않는다") {
                @Override
                boolean violates(JavaClass item) {
                    String relative = relativePackage(root, item);
                    int at = ("." + relative).indexOf(".application.");
                    if (at < 0) {
                        return false;
                    }
                    String below = ("." + relative).substring(at + ".application.".length());
                    return !below.equals("dto") && !below.equals("port");
                }


                @Override
                String message(String packageName) {
                    return packageName + " 는 application 의 하위 패키지로 허용되지 않는다. application 아래에는 dto 와 port 만 둔다. "
                        + "model 은 dto 로, 서비스는 application 으로, 인터페이스는 port 로 옮긴다.";
                }
            })
            .as("application 아래 하위 패키지는 dto 와 port 뿐이다");
    }


    /** rule-72: port 에는 인터페이스와 그 예외만 둔다. */
    static ArchRule portPackageHoldsOnlyInterfaces(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("port 패키지에는 인터페이스와 그 예외만 둔다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (item.getEnclosingClass().isPresent() || !item.getPackageName().endsWith(".application.port")) {
                        return;
                    }
                    if (!item.isInterface() && !item.isAssignableTo(Throwable.class)) {
                        report(item, events, item.getName() + " 는 port 패키지에 있지만 인터페이스가 아니다. "
                            + "값은 application.dto 로, 빈은 application 으로 옮긴다.");
                    }
                }
            })
            .as("port 패키지에는 인터페이스와 그 예외만 둔다");
    }


    /** rule-73: dto 에는 값만 둔다. */
    static ArchRule dtoPackageHoldsOnlyValues(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("dto 패키지에는 빈과 엔티티와 인터페이스를 두지 않는다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (item.getEnclosingClass().isPresent() || !item.getPackageName().endsWith(".application.dto")) {
                        return;
                    }
                    if (isBean(item) || item.isAnnotatedWith(Entity.class) || (item.isInterface() && !item.isSealed())) {
                        report(item, events, item.getName() + " 는 dto 패키지에 있지만 값이 아니다. "
                            + "빈은 application 으로, 인터페이스는 application.port 로 옮긴다.");
                    }
                }
            })
            .as("dto 패키지에는 값만 둔다");
    }


    /** rule-74: 설정 값은 config 에 둔다. */
    static ArchRule configurationPropertiesInConfig(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("ConfigurationProperties 는 config 패키지에 둔다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (!item.isAnnotatedWith(ConfigurationProperties.class) || item.getPackageName().startsWith(root + ".shared.")) {
                        return;
                    }
                    String last = item.getPackageName().substring(item.getPackageName().lastIndexOf('.') + 1);
                    if (!last.equals("config")) {
                        report(item, events, item.getName() + " 는 @ConfigurationProperties 인데 config 패키지 밖에 있다. "
                            + destination(root, item, "config") + " 로 옮긴다.");
                    }
                }
            })
            .as("ConfigurationProperties 는 config 패키지에 둔다");
    }


    /** rule-5: 기능 안의 일곱 층이 정해진 방향으로만 서로를 쓴다. */
    static ArchRule layerDirection(String root) {
        return layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("presentation")
            .definedBy(root + "..presentation..")
            .layer("application")
            .definedBy(resideInAPackage(root + "..application..")
                .and(resideOutsideOfPackages(root + "..application.port..", root + "..application.dto..")))
            .layer("port")
            .definedBy(root + "..application.port..")
            .layer("dto")
            .definedBy(root + "..application.dto..")
            .layer("infra")
            .definedBy(root + "..infra..")
            .layer("domain")
            .definedBy(root + "..domain..")
            .layer("config")
            .definedBy(resideInAPackage(root + "..config..").and(resideOutsideOfPackages(root + ".shared..")))
            .whereLayer("presentation")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("application")
            .mayOnlyBeAccessedByLayers("presentation")
            .whereLayer("port")
            .mayOnlyBeAccessedByLayers("presentation", "application", "dto", "infra", "config")
            .whereLayer("dto")
            .mayOnlyBeAccessedByLayers("presentation", "application", "port", "infra", "config")
            .whereLayer("infra")
            .mayOnlyBeAccessedByLayers("application", "config")
            .whereLayer("domain")
            .mayOnlyBeAccessedByLayers("presentation", "application", "port", "dto", "infra", "config")
            .whereLayer("config")
            .mayOnlyBeAccessedByLayers("presentation", "application", "infra")
            .as("층은 presentation 에서 application 을 거쳐 port, dto, infra, domain, config 로 흐른다. "
                + "infra 와 domain 은 application 의 빈을 쓰지 않는다. "
                + "막힌 의존은 application.port 에 인터페이스를 두고 구현을 infra 에 두어 끊는다");
    }


    /** rule-75: application 직하의 클래스는 25개를 넘지 않는다. */
    static ArchRule applicationPackageSize(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("application 직하의 클래스가 상한을 넘지 않는다") {
                private final Map<String, Integer> counts = new TreeMap<>();


                @Override
                public void init(Collection<JavaClass> allObjectsToTest) {
                    counts.clear();
                }


                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (item.getEnclosingClass().isEmpty() && item.getPackageName().endsWith(".application")) {
                        counts.merge(item.getPackageName(), 1, Integer::sum);
                    }
                }


                @Override
                public void finish(ConditionEvents events) {
                    counts.forEach((packageName, count) -> {
                        if (count > APPLICATION_SIZE_LIMIT) {
                            events.add(SimpleConditionEvent.violated(packageName, packageName
                                + " 는 application 직하의 클래스가 상한(" + APPLICATION_SIZE_LIMIT + ")을 넘었다. "
                                + "값과 인터페이스는 dto 와 port 로 옮기고, 그래도 넘으면 하위 기능으로 나눈다."));
                        }
                    });
                }
            })
            .as("application 직하의 클래스는 상한을 넘지 않는다");
    }


    /** rule-76: 하위 기능은 순서의 아래쪽만 쓴다. */
    static ArchRule subFeaturesFollowOrder(String root, Map<String, List<String>> order) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("하위 기능이 같은 기능의 아래 하위 기능만 쓴다") {
                private final Set<String> unknown = new TreeSet<>();


                @Override
                public void init(Collection<JavaClass> allObjectsToTest) {
                    unknown.clear();
                }


                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    Optional<String[]> from = subFeatureOf(root, order, item);
                    if (from.isEmpty()) {
                        return;
                    }
                    String feature = from.get()[0];
                    String sub = from.get()[1];
                    List<String> sequence = order.get(feature);
                    if (!sequence.contains(sub)) {
                        unknown.add(feature + "." + sub);
                        return;
                    }
                    Set<String> reported = new HashSet<>();
                    for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                        JavaClass target = dependency.getTargetClass().getBaseComponentType();
                        Optional<String[]> to = subFeatureOf(root, order, target);
                        if (to.isEmpty() || !to.get()[0].equals(feature) || !sequence.contains(to.get()[1])) {
                            continue;
                        }
                        if (sequence.indexOf(to.get()[1]) > sequence.indexOf(sub)
                            && reported.add(outermost(target).getName())) {
                            report(item, events, feature + "." + sub + " 의 " + outermost(item).getName()
                                + " 가 위 하위 기능 " + feature + "." + to.get()[1] + " 의 " + outermost(target).getName()
                                + " 를 쓴다. 이 기능의 하위 기능은 아래쪽만 쓴다(순서: " + String.join(", ", sequence) + "). "
                                + "아래 하위 기능의 application.port 에 인터페이스를 두고 위에서 구현하거나, 타입을 아래로 옮긴다.");
                        }
                    }
                }


                @Override
                public void finish(ConditionEvents events) {
                    unknown.forEach(name -> events.add(SimpleConditionEvent.violated(name,
                        name + " 은 순서에 없다. SubFeatureOrder 에 자리를 정해 넣는다.")));
                }
            })
            .as("하위 기능은 같은 기능의 아래 하위 기능만 쓴다");
    }


    /** rule-77: 나눈 기능은 루트에 역할 패키지를 두지 않는다. */
    static ArchRule splitFeaturesHaveNoRootRoles(String root, Map<String, List<String>> order) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new PackageCollector("나눈 기능의 루트에 역할 패키지를 두지 않는다") {
                @Override
                boolean violates(JavaClass item) {
                    List<String> segments = segments(root, item);
                    return segments.size() >= 2 && order.containsKey(segments.get(0)) && ROLE_PACKAGES.contains(segments.get(1));
                }


                @Override
                String message(String packageName) {
                    String relative = packageName.substring(root.length() + 1);
                    String feature = relative.substring(0, relative.indexOf('.'));
                    return relative + " 에 클래스가 남아 있다. " + feature + " 는 하위 기능으로 나뉜 기능이라 루트에 역할 패키지를 두지 않는다. "
                        + "클래스는 " + feature + ".<하위 기능>.<역할> 로 옮긴다. 어느 하위 기능인지는 ADR-20261015 의 「하위 기능」 표를 본다.";
                }
            })
            .as("나눈 기능은 루트에 역할 패키지를 두지 않는다");
    }


    /** rule-78: 운영 클래스의 단순 이름은 겹치지 않는다. */
    static ArchRule simpleClassNamesAreUnique(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("최상위 클래스의 단순 이름이 겹치지 않는다") {
                private final Map<String, Set<String>> byName = new TreeMap<>();


                @Override
                public void init(Collection<JavaClass> allObjectsToTest) {
                    byName.clear();
                }


                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (item.getEnclosingClass().isEmpty() && !item.getSimpleName().equals("package-info")) {
                        byName.computeIfAbsent(item.getSimpleName(), ignored -> new TreeSet<>()).add(item.getName());
                    }
                }


                @Override
                public void finish(ConditionEvents events) {
                    byName.forEach((name, fullNames) -> {
                        if (fullNames.size() < 2) {
                            return;
                        }
                        List<String> sorted = List.copyOf(fullNames);
                        for (String other : sorted.subList(1, sorted.size())) {
                            events.add(SimpleConditionEvent.violated(name, name + " 가 " + sorted.get(0) + " 와 " + other
                                + " 에 있다. 기준 파일 대조가 단순 이름에 기대므로 클래스 이름은 운영 코드 전체에서 유일해야 한다. 한쪽의 이름을 바꾼다."));
                        }
                    });
                }
            })
            .as("운영 클래스의 단순 이름은 겹치지 않는다");
    }


    /** rule-79: admin 은 하위 기능 아래에 application, application.dto, presentation 만 둔다. */
    static ArchRule adminPackageShape(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("admin 은 application, application.dto, presentation 만 둔다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    List<String> segments = segments(root, item);
                    if (item.getEnclosingClass().isPresent() || segments.isEmpty() || !segments.get(0).equals(ADMIN)) {
                        return;
                    }
                    String below = String.join(".", segments.subList(Math.min(2, segments.size()), segments.size()));
                    boolean allowed = segments.size() >= 3
                        && Set.of("application", "application.dto", "presentation").contains(below);
                    if (!allowed) {
                        report(item, events, item.getName() + " 는 admin 에 있지만 허용된 패키지가 아니다. "
                            + "admin 은 application 과 presentation 만 갖는다. 엔티티와 저장소와 설정은 기능이 갖는다.");
                    }
                }
            })
            .as("admin 은 하위 기능 아래에 application 과 presentation 만 둔다");
    }


    /** rule-80: admin 밖의 클래스는 admin 을 쓰지 않는다. */
    static ArchRule featuresDoNotDependOnAdmin(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("admin 밖의 클래스가 admin 을 쓰지 않는다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (isAdmin(root, item)) {
                        return;
                    }
                    Set<String> reported = new HashSet<>();
                    for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                        JavaClass target = dependency.getTargetClass().getBaseComponentType();
                        if (isAdmin(root, target) && reported.add(outermost(target).getName())) {
                            report(item, events, outermost(item).getName() + " 가 admin 의 " + outermost(target).getName()
                                + " 를 쓴다. 기능은 admin 을 모른다. 의존을 거꾸로 하거나 공통 값은 기능의 application.dto 에 둔다.");
                        }
                    }
                }
            })
            .as("기능은 admin 을 쓰지 않는다");
    }


    /** rule-81: admin 은 기능의 application 과 application.dto 와 domain.type 만 쓴다. */
    static ArchRule adminUsesOnlyFeatureApplication(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("admin 이 기능의 application 과 application.dto 와 domain.type 만 쓴다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (!isAdmin(root, item)) {
                        return;
                    }
                    Set<String> reported = new HashSet<>();
                    for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                        JavaClass target = dependency.getTargetClass().getBaseComponentType();
                        List<String> segments = segments(root, target);
                        if (segments.size() < 2 || segments.get(0).equals(ADMIN) || segments.get(0).equals("shared")
                            || usableByAdmin(String.join(".", segments.subList(1, segments.size())))
                            || !reported.add(outermost(target).getName())) {
                            continue;
                        }
                        report(item, events, outermost(item).getName() + " 가 " + segments.get(0) + " 의 "
                            + outermost(target).getName() + " 를 쓴다. admin 은 기능의 application 과 application.dto 만 부른다. "
                            + "필요한 값은 그 기능의 application 이 dto 로 내놓게 한다. 응답 모양은 admin 의 *Dtos 에 둔다.");
                    }
                }
            })
            .as("admin 은 기능의 application 과 application.dto 만 쓴다");
    }


    /** rule-82: admin 의 엔드포인트는 requireAdmin 을 부른다. */
    static ArchRule adminEndpointsRequireAdmin(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("admin 의 엔드포인트가 requireAdmin 을 부른다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (!isAdmin(root, item) || !item.getPackageName().endsWith(".presentation")) {
                        return;
                    }
                    for (JavaMethod method : item.getMethods()) {
                        if (mapped(method) && !callsRequireAdmin(root, method)) {
                            report(item, events, item.getName() + "." + method.getName()
                                + " 가 requireAdmin 을 부르지 않는다. admin 의 엔드포인트는 첫 줄에서 requireAdmin 을 부른다.");
                        }
                    }
                }
            })
            .as("admin 의 엔드포인트는 requireAdmin 을 부른다");
    }


    /** rule-83: requireAdmin 은 admin 안에서만 부른다. */
    static ArchRule requireAdminOnlyInAdmin(String root) {
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("requireAdmin 을 admin 안에서만 부른다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (isAdmin(root, item) || item.getPackageName().startsWith(root + ".shared.auth")) {
                        return;
                    }
                    Set<String> reported = new HashSet<>();
                    for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                        if (isRequireAdmin(root, call) && reported.add(call.getOrigin().getName())) {
                            report(item, events, outermost(item).getName() + "." + call.getOrigin().getName()
                                + " 가 CurrentUserProvider.requireAdmin 을 부른다. 관리자 전용 API 는 admin.<하위 기능>.presentation 에 두고 "
                                + "requireAdmin 은 거기서만 부른다. 사용자 기능 안에서 권한에 따라 범위만 다르면 CurrentUser 의 역할로 판정한다.");
                        }
                    }
                }
            })
            .as("requireAdmin 은 admin 안에서만 부른다");
    }


    private static boolean isBean(JavaClass item) {
        return item.isAnnotatedWith(Component.class) || item.isMetaAnnotatedWith(Component.class);
    }


    private static boolean isAdmin(String root, JavaClass item) {
        return item.getPackageName().equals(root + "." + ADMIN) || item.getPackageName().startsWith(root + "." + ADMIN + ".");
    }


    private static boolean usableByAdmin(String featurePackage) {
        return featurePackage.equals("application")
            || featurePackage.endsWith(".application")
            || featurePackage.equals("application.dto")
            || featurePackage.endsWith(".application.dto")
            || featurePackage.contains("domain.type")
            || TRANSITIONAL_APPLICATION_PACKAGES.stream().anyMatch(transitional -> featurePackage.equals(transitional) || featurePackage.endsWith("." + transitional));
    }


    private static boolean mapped(JavaMethod method) {
        return method.isAnnotatedWith(RequestMapping.class) || method.isMetaAnnotatedWith(RequestMapping.class);
    }


    private static boolean isRequireAdmin(String root, JavaMethodCall call) {
        return call.getTargetOwner().getName().equals(root + CURRENT_USER_PROVIDER) && call.getName().equals("requireAdmin");
    }


    /** 같은 클래스의 메서드를 거쳐 가는 호출까지 따라가 requireAdmin 이 닿는지 본다. */
    private static boolean callsRequireAdmin(String root, JavaMethod start) {
        Deque<JavaCodeUnit> pending = new ArrayDeque<>(List.of(start));
        Set<JavaCodeUnit> seen = new HashSet<>(pending);
        while (!pending.isEmpty()) {
            JavaCodeUnit unit = pending.poll();
            for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                if (isRequireAdmin(root, call)) {
                    return true;
                }
                if (call.getTargetOwner().equals(start.getOwner())) {
                    call.getTarget().resolveMember().ifPresent(member -> {
                        if (seen.add(member)) {
                            pending.add(member);
                        }
                    });
                }
            }
        }
        return false;
    }


    private static void report(JavaClass item, ConditionEvents events, String message) {
        events.add(SimpleConditionEvent.violated(item, message));
    }


    private static JavaClass outermost(JavaClass item) {
        JavaClass current = item;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }


    /** root 아래의 패키지 마디다. root 밖이거나 root 자신이면 비어 있다. */
    private static List<String> segments(String root, JavaClass item) {
        String relative = relativePackage(root, item);
        return relative.isEmpty() ? List.of() : List.of(relative.split("\\."));
    }


    private static String relativePackage(String root, JavaClass item) {
        String packageName = item.getPackageName();
        return packageName.startsWith(root + ".") ? packageName.substring(root.length() + 1) : "";
    }


    /** 하위 기능으로 나뉜 기능의 하위 기능 패키지에 있으면 {기능, 하위 기능}, 아니면 비어 있다. 역할 패키지는 하위 기능이 아니다. */
    private static Optional<String[]> subFeatureOf(String root, Map<String, List<String>> order, JavaClass item) {
        List<String> segments = segments(root, item);
        if (segments.size() < 2 || !order.containsKey(segments.get(0)) || ROLE_PACKAGES.contains(segments.get(1))) {
            return Optional.empty();
        }
        return Optional.of(new String[] {segments.get(0), segments.get(1)});
    }


    /**
     * 옮길 곳의 패키지 이름을 안내용으로 만든다. 나뉜 기능은 하위 기능이 정해진 클래스면 그 이름을, 아니면 {@code <하위 기능>} 을 쓴다.
     */
    private static String destination(String root, JavaClass item, String role) {
        List<String> segments = segments(root, item);
        if (segments.isEmpty()) {
            return role;
        }
        String feature = segments.get(0);
        if (SubFeatureOrder.ORDER.containsKey(feature)) {
            Optional<String[]> sub = subFeatureOf(root, SubFeatureOrder.ORDER, item);
            return feature + "." + sub.map(found -> found[1]).orElse("<하위 기능>") + "." + role;
        }
        return feature + "." + role;
    }


    /** 위반한 패키지마다 한 줄을 내는 조건이다. */
    private abstract static class PackageCollector extends ArchCondition<JavaClass> {
        private final Set<String> packages = new TreeSet<>();


        PackageCollector(String description) {
            super(description);
        }


        abstract boolean violates(JavaClass item);


        abstract String message(String packageName);


        @Override
        public void init(Collection<JavaClass> allObjectsToTest) {
            packages.clear();
        }


        @Override
        public void check(JavaClass item, ConditionEvents events) {
            if (violates(item)) {
                packages.add(item.getPackageName());
            }
        }


        @Override
        public void finish(ConditionEvents events) {
            packages.forEach(packageName -> events.add(SimpleConditionEvent.violated(packageName, message(packageName))));
        }
    }
}
