package com.bifos.assistant.testsupport;

import com.bifos.assistant.connector.application.ConnectorPolicyProperties;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.proactive.application.ProactiveCheckProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 컨텍스트를 함께 쓰는 검사 사이에 남는 것을 정리하고, 검사가 정한 설정을 적용한다.
 *
 * <p>turn, 위임, 살펴보기 같은 요청 밖 작업은 가상 스레드에서 돈다. 검사가 끝난 뒤에도 돌던 작업은 다음 검사가 정한 대역 응답을 가져가거나
 * 대역에 받은 명령을 남긴다. 그래서 검사가 끝나면 {@link TrackingBackgroundTasks} 가 쥔 스레드가 모두 끝날 때까지 join 한 뒤 대역과
 * 시계, 꺼 둔 대역, 설정을 되돌린다(ADR-20261007 / background-tasks). 상한 안에 끝나지 않으면 그 검사를 실패로 둔다. 남은 작업을 알리지 않고 넘기면 원인과 먼 다음 검사가
 * 흔들린다. 상한은 판정 기준이 아니라 멈춘 작업을 잡는 안전장치다.
 *
 * <p>검사가 시작하기 전에 {@link OverrideProperties} 값을 {@link OverridableLiveProperties} 에 넣는다(ADR-20261007 /
 * live-properties). 설정을 되돌리는 것은 join 뒤다. 앞 검사의 작업이 돌던 중에 설정이 바뀌면 그 작업이 다른 값을 읽는다.
 *
 * <p>Mockito mock 과 spy 는 Spring 이 검사마다 초기화하므로 여기서 다루지 않는다.
 */
public class IntegrationTestIsolation implements BeforeEachCallback, AfterEachCallback {

    private static final Duration IDLE_LIMIT = Duration.ofSeconds(30);

    /**
     * {@link LiveProperties} 로 읽는 칸이 일부뿐인 설정이다. 여기 적은 키만 바꿀 수 있다.
     *
     * <p>커넥터 정책의 {@code expire-cron} 은 {@code @Scheduled} 가 기동 때 한 번 읽으므로 바꿔도 일정이 그대로다. 그래서 목록에
     * 넣지 않는다.
     */
    private static final Map<Class<?>, Set<ConfigurationPropertyName>> PARTIAL = Map.of(
            HermesProperties.class,
            Set.of(
                    ConfigurationPropertyName.of("hermes.run-timeout"),
                    ConfigurationPropertyName.of("hermes.poll-interval")),
            ConnectorPolicyProperties.class,
            Set.of(
                    ConfigurationPropertyName.of("assistant.connector.policy.catalog-ttl"),
                    ConfigurationPropertyName.of("assistant.connector.policy.catalog-failure-ttl"),
                    ConfigurationPropertyName.of("assistant.connector.policy.approval-ttl")));

    @Override
    public void beforeEach(ExtensionContext context) {
        ApplicationContext applicationContext = SpringExtension.getApplicationContext(context);
        reset(applicationContext);
        apply(applicationContext, overridesOf(context.getRequiredTestClass()));
    }

    @Override
    public void afterEach(ExtensionContext context) throws InterruptedException {
        afterTest(SpringExtension.getApplicationContext(context), IDLE_LIMIT);
    }

    /**
     * 띄운 작업이 모두 끝나기를 기다린 뒤 대역과 시계, 설정을 되돌린다. 기다리다 실패해도 되돌린다.
     *
     * @throws AssertionError 상한이 지나도 끝나지 않은 작업이 있을 때. 메시지에 그 스레드 이름이 있다
     */
    static void afterTest(ApplicationContext context, Duration limit) throws InterruptedException {
        try {
            context.getBean(TrackingBackgroundTasks.class).awaitIdle(limit);
        } finally {
            reset(context);
        }
    }

    /**
     * 검사 클래스와 그 상위 클래스, 메타 주석에 단 {@link OverrideProperties} 값을 모은다. 검사 클래스에 가까운 값이 앞에 온다.
     */
    static List<String> overridesOf(Class<?> testClass) {
        return MergedAnnotations.from(testClass, SearchStrategy.TYPE_HIERARCHY).stream(OverrideProperties.class)
                .flatMap(annotation -> Arrays.stream(annotation.getStringArray("value")))
                .toList();
    }

    /**
     * {@code "<키>=<값>"} 들을 설정 record 로 바인딩해 해당 {@link OverridableLiveProperties} 에 넣는다. 바꾼 record 는 운영과 같은
     * 생성자 검증을 거친다. 같은 키가 여럿이면 앞의 값이 이긴다. 하나라도 실패하면 아무것도 바꾸지 않는다.
     *
     * @throws IllegalArgumentException {@link LiveProperties} 로 읽지 않는 키이거나 모양이 틀렸을 때. 메시지에 그 키가 있다
     */
    static void apply(ApplicationContext context, List<String> overrides) {
        if (overrides.isEmpty()) {
            return;
        }
        List<OverridableLiveProperties<?>> lives = overridables(context);
        Map<OverridableLiveProperties<?>, Map<String, String>> grouped = new LinkedHashMap<>();
        for (String override : overrides) {
            int separator = override.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("@OverrideProperties 값은 <키>=<값> 모양이어야 한다: " + override);
            }
            String key = override.substring(0, separator).strip();
            String value = override.substring(separator + 1);
            ConfigurationPropertyName name = ConfigurationPropertyName.of(key);
            List<OverridableLiveProperties<?>> owners = lives.stream()
                    .filter(live -> prefixOf(live.type()).isAncestorOf(name))
                    .toList();
            if (owners.isEmpty()) {
                throw new IllegalArgumentException("LiveProperties 로 읽지 않는 설정이다: " + key);
            }
            for (OverridableLiveProperties<?> owner : owners) {
                Set<ConfigurationPropertyName> allowed = PARTIAL.get(owner.type());
                if (allowed != null && !allowed.contains(name)) {
                    throw new IllegalArgumentException("LiveProperties 로 읽지 않는 설정이다: " + key);
                }
                grouped.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).putIfAbsent(key, value);
            }
        }

        ConfigurableEnvironment environment = (ConfigurableEnvironment) context.getEnvironment();
        Map<OverridableLiveProperties<?>, Object> bound = new LinkedHashMap<>();
        grouped.forEach((live, values) -> bound.put(live, bind(environment, live.type(), values)));
        requireRunTimeoutOrder(lives, bound);
        bound.forEach(OverridableLiveProperties::override);
    }

    /**
     * 설정을 먼저 되돌리고 대역을 하나씩 되돌린다. 한 항목이 던져도 나머지는 되돌린다. 남은 대역과 설정은 원인과 먼 다음 검사를 흔든다.
     *
     * @throws RuntimeException 처음 던진 것. {@link Error} 이면 그대로 던진다. 그 뒤에 던진 것은 suppressed 로 붙는다
     */
    private static void reset(ApplicationContext context) {
        List<Throwable> failures = new ArrayList<>();
        attempt(failures, () -> overridables(context).forEach(OverridableLiveProperties::reset));
        attempt(failures, () -> context.getBean(StubHermesRunsClient.class).reset());
        attempt(failures, () -> context.getBean(TestClock.class).reset());
        attempt(failures, () -> context.getBean(CapturingTaskScheduler.class).reset());
        attempt(failures, () -> context.getBean(WakeRetryThreads.class).reset());
        attempt(failures, () -> context.getBean(ConnectorChangeRecorder.class).reset());
        attempt(failures, () -> context.getBean(FailingAccessRevoker.class).reset());
        attempt(failures, () -> context.getBean(TestAutoTurnResultSource.class).reset());
        attempt(
                failures,
                () -> context.getBean(AttentionTestCandidates.FailingCandidates.class)
                        .reset());
        attempt(
                failures,
                () -> context.getBean(AttentionTestCandidates.ReadCountingCandidates.class)
                        .reset());
        if (failures.isEmpty()) {
            return;
        }
        Throwable first = failures.getFirst();
        failures.subList(1, failures.size()).forEach(first::addSuppressed);
        if (first instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw (Error) first;
    }

    /** 되돌리기 하나를 돌리고, 던지면 모아 둔다. 단언 실패 같은 {@link Error} 도 모아 뒤 항목을 막지 않는다. */
    private static void attempt(List<Throwable> failures, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | Error ex) {
            failures.add(ex);
        }
    }

    private static List<OverridableLiveProperties<?>> overridables(ApplicationContext context) {
        List<OverridableLiveProperties<?>> lives = new ArrayList<>();
        for (Object bean : context.getBeansOfType(LiveProperties.class).values()) {
            if (bean instanceof OverridableLiveProperties<?> live) {
                lives.add(live);
            }
        }
        return lives;
    }

    private static ConfigurationPropertyName prefixOf(Class<?> type) {
        String prefix =
                MergedAnnotations.from(type).get(ConfigurationProperties.class).getString("prefix");
        return ConfigurationPropertyName.of(prefix);
    }

    /** 바꿀 값을 앞에, 컨텍스트의 설정을 뒤에 두어 그 record 의 prefix 로 바인딩한다. */
    private static Object bind(ConfigurableEnvironment environment, Class<?> type, Map<String, String> values) {
        List<ConfigurationPropertySource> sources = new ArrayList<>();
        sources.add(new MapConfigurationPropertySource(values));
        ConfigurationPropertySources.get(environment).forEach(sources::add);
        Binder binder = new Binder(
                sources,
                new PropertySourcesPlaceholdersResolver(environment),
                ApplicationConversionService.getSharedInstance());
        return binder.bindOrCreate(prefixOf(type).toString(), Bindable.of(type));
    }

    /** 운영 기동 검사({@link ProactiveCheckProperties.RunTimeoutCheck})와 같은 조건을 바꾼 값으로 다시 확인한다. */
    private static void requireRunTimeoutOrder(
            List<OverridableLiveProperties<?>> lives, Map<OverridableLiveProperties<?>, Object> bound) {
        boolean touched = bound.keySet().stream()
                .anyMatch(
                        live -> live.type() == ProactiveCheckProperties.class || live.type() == HermesProperties.class);
        if (!touched) {
            return;
        }
        ProactiveCheckProperties proactive = valueAfter(lives, bound, ProactiveCheckProperties.class);
        HermesProperties hermes = valueAfter(lives, bound, HermesProperties.class);
        proactive.requireShorterThan(hermes.runTimeout());
    }

    private static <T> T valueAfter(
            List<OverridableLiveProperties<?>> lives, Map<OverridableLiveProperties<?>, Object> bound, Class<T> type) {
        OverridableLiveProperties<?> live = lives.stream()
                .filter(candidate -> candidate.type() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("LiveProperties 빈이 없다: " + type.getName()));
        return type.cast(bound.getOrDefault(live, live.current()));
    }
}
