package com.bifos.assistant.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

/** 현재 이동한 두 설정과 새 config 범위만 검사한다. 기존 전역 기준은 바꾸지 않는다. */
final class AgentConfigPlacement extends ArchCondition<JavaClass> {

    private static final String OWNER = "com.bifos.assistant.agent.config";
    private static final Map<String, String> PREFIXES = Map.of(
        "AgentProperties", "assistant.agents", "PeopleProperties", "assistant.people");
    private final Map<String, Integer> counts = new HashMap<>();


    AgentConfigPlacement() {
        super("에이전트 설정의 소유와 역할을 지킨다");
    }


    @Override
    public void init(Collection<JavaClass> allObjectsToTest) {
        counts.clear();
    }


    @Override
    public void check(JavaClass type, ConditionEvents events) {
        boolean config = type.getPackageName().equals(OWNER) || type.getPackageName().startsWith(OWNER + ".");
        boolean properties = type.isAnnotatedWith(ConfigurationProperties.class);
        String prefix = "";
        if (properties) {
            var annotation = type.getAnnotationOfType(ConfigurationProperties.class);
            require(type, annotation.prefix().isEmpty() || annotation.value().isEmpty()
                || annotation.prefix().equals(annotation.value()), events, "설정 prefix와 value는 충돌하지 않음");
            prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
        }
        boolean owned = !type.isNestedClass() && PREFIXES.containsKey(type.getSimpleName());
        if (owned) {
            counts.merge(type.getSimpleName(), 1, Integer::sum);
            require(type, type.getPackageName().equals(OWNER), events, "설정 소유 package는 " + OWNER);
            require(type, type.isRecord() && type.getModifiers().contains(JavaModifier.PUBLIC), events,
                "설정은 public record");
            require(type, properties && PREFIXES.get(type.getSimpleName()).equals(prefix), events,
                "설정 prefix는 " + PREFIXES.get(type.getSimpleName()));
            require(type, type.isAnnotatedWith(Validated.class), events, "설정은 Validated");
        }
        if (PREFIXES.containsValue(prefix)) {
            require(type, owned, events, "에이전트 prefix는 지정한 두 설정 타입만 선언");
        }
        if (config) {
            require(type, properties || type.isAnnotatedWith(Configuration.class) || isOwnedNestedValue(type), events,
                "config에는 ConfigurationProperties 또는 Configuration과 소유 설정의 중첩 값 record만 배치");
            require(type, !type.isAnnotatedWith(Entity.class) && !type.isAnnotatedWith(Embeddable.class)
                && !type.isAnnotatedWith(Service.class) && !type.isAnnotatedWith(Repository.class)
                && !type.isAnnotatedWith(Controller.class)
                && !type.isAnnotatedWith(RestController.class), events,
                "config에 Entity, Embeddable, Service, Repository, Controller, RestController를 배치하지 않음");
        }
    }


    private static boolean isOwnedNestedValue(JavaClass type) {
        if (!type.isRecord()) {
            return false;
        }
        var enclosing = type.getEnclosingClass();
        while (enclosing.isPresent() && enclosing.get().isRecord()) {
            JavaClass owner = enclosing.get();
            if (!owner.isNestedClass()) {
                return owner.getPackageName().equals(OWNER) && PREFIXES.containsKey(owner.getSimpleName())
                    && owner.isAnnotatedWith(ConfigurationProperties.class);
            }
            enclosing = owner.getEnclosingClass();
        }
        return false;
    }


    @Override
    public void finish(ConditionEvents events) {
        for (String name : PREFIXES.keySet()) {
            int count = counts.getOrDefault(name, 0);
            if (count != 1) {
                events.add(SimpleConditionEvent.violated(OWNER + "." + name,
                    OWNER + "." + name + " 선언은 정확히 1개여야 한다: " + count));
            }
        }
    }


    private static void require(JavaClass type, boolean valid, ConditionEvents events, String requirement) {
        if (!valid) {
            events.add(SimpleConditionEvent.violated(type, type.getName() + ": " + requirement));
        }
    }
}
