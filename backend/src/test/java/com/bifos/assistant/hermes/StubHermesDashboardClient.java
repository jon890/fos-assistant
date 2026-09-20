package com.bifos.assistant.hermes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 대시보드를 부르지 않고 무엇이 불렸는지만 기록하는 대역이다.
 *
 * <p>{@code .env} 에 실제로 들어간 것을 단언할 수 있어야 하므로 profile 마다 쓴 값을 그대로 담는다.
 * 어느 호출에서 실패할지는 {@code failOn*} 으로 정한다.
 */
public class StubHermesDashboardClient implements HermesDashboardClient {

    private final List<String> createdProfiles = new ArrayList<>();
    private final List<String> deletedProfiles = new ArrayList<>();
    private final Map<String, Map<String, String>> env = new LinkedHashMap<>();

    private Supplier<RuntimeException> createFailure;
    private Supplier<RuntimeException> putEnvFailure;
    private Supplier<RuntimeException> deleteFailure;

    @Override
    public void createProfile(String name) {
        if (createFailure != null) {
            throw createFailure.get();
        }
        createdProfiles.add(name);
        env.put(name, new LinkedHashMap<>());
    }

    @Override
    public void putEnv(String profile, String key, String value) {
        if (putEnvFailure != null) {
            throw putEnvFailure.get();
        }
        env.computeIfAbsent(profile, ignored -> new LinkedHashMap<>()).put(key, value);
    }

    @Override
    public void deleteProfile(String name) {
        if (deleteFailure != null) {
            throw deleteFailure.get();
        }
        deletedProfiles.add(name);
        env.remove(name);
    }

    public void failOnCreate(Supplier<RuntimeException> failure) {
        this.createFailure = failure;
    }

    public void failOnPutEnv(Supplier<RuntimeException> failure) {
        this.putEnvFailure = failure;
    }

    public void failOnDelete(Supplier<RuntimeException> failure) {
        this.deleteFailure = failure;
    }

    /** 되돌린 뒤 같은 이름으로 다시 만드는 것을 검사할 때 쓴다. 기록은 그대로 둔다. */
    public void stopFailing() {
        this.createFailure = null;
        this.putEnvFailure = null;
        this.deleteFailure = null;
    }

    public List<String> createdProfiles() {
        return List.copyOf(createdProfiles);
    }

    public List<String> deletedProfiles() {
        return List.copyOf(deletedProfiles);
    }

    /** 그 profile 의 {@code .env} 에 들어간 것이다. 아직 만든 적이 없으면 비어 있다. */
    public Map<String, String> env(String profile) {
        return Map.copyOf(env.getOrDefault(profile, Map.of()));
    }
}
