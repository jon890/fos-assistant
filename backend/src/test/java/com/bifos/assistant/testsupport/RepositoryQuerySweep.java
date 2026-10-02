package com.bifos.assistant.testsupport;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.BaseStream;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 문맥에 있는 모든 저장소 인터페이스가 선언한 메서드를 한 번씩 실행한다.
 *
 * <p>쿼리가 데이터베이스에서 실행되는지만 본다. 인자는 타입에 맞춰 스스로 만들고 결과 값은 보지 않는다. 메서드마다 트랜잭션을 열어
 * 되돌리므로 실행한 것이 남지 않는다. 인자를 만들지 못한 메서드는 건너뛰지 않고 {@link Result#unsupported} 에 담아 부른 쪽이
 * 실패시키게 한다.
 */
public class RepositoryQuerySweep {

    private static final String BASE_PACKAGE = "com.bifos.assistant";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID FIXED_UUID = new UUID(0L, 1L);

    private final ApplicationContext context;
    private final TransactionTemplate transaction;

    public RepositoryQuerySweep(ApplicationContext context, PlatformTransactionManager transactionManager) {
        this.context = context;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param repositories 찾은 저장소 인터페이스의 단순 이름. 선언 메서드가 없는 저장소도 담는다
     * @param executed 예외 없이 끝난 메서드
     * @param failures 예외로 끝난 메서드와 그 예외
     * @param unsupported 인자를 만들지 못한 메서드와 그 타입
     * @param staleExclusions 제외 목록에 있는데 찾지 못한 이름
     */
    public record Result(
            List<String> repositories,
            List<String> executed,
            List<Failure> failures,
            List<String> unsupported,
            Set<String> staleExclusions) {}

    /** 메서드 이름은 {@code 인터페이스 단순 이름.메서드 이름} 이다. */
    public record Failure(String method, Throwable cause) {}

    /**
     * 저장소 메서드를 모두 실행한 뒤 결과를 준다. 실패한 쿼리가 뒤의 메서드를 막지 않는다.
     *
     * @param excluded 실행하지 않을 메서드 이름
     * @throws IllegalStateException 같은 이름의 메서드가 둘이면 던진다. 이름으로 구분하지 못하는 메서드는 제외 목록과 보고에서 섞인다
     */
    public Result run(Set<String> excluded) {
        List<String> repositories = new ArrayList<>();
        Map<String, Target> targets = new LinkedHashMap<>();
        for (Class<?> repositoryInterface : repositoryInterfaces()) {
            repositories.add(repositoryInterface.getSimpleName());
            Object proxy = context.getBean(repositoryInterface);
            for (Method method : declaredQueryMethods(repositoryInterface)) {
                String name = repositoryInterface.getSimpleName() + "." + method.getName();
                if (targets.put(name, new Target(proxy, method)) != null) {
                    throw new IllegalStateException("저장소 메서드 이름이 겹친다: " + name);
                }
            }
        }

        List<String> executed = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        targets.forEach((name, target) -> {
            if (excluded.contains(name)) {
                return;
            }
            Object[] arguments;
            try {
                arguments = arguments(target.method());
            } catch (UnsupportedTypeException e) {
                unsupported.add(name + " (" + e.getMessage() + ")");
                return;
            }
            Throwable failure = invoke(target, arguments);
            if (failure == null) {
                executed.add(name);
            } else {
                failures.add(new Failure(name, failure));
            }
        });

        Set<String> staleExclusions = new TreeSet<>(excluded);
        staleExclusions.removeAll(targets.keySet());
        return new Result(
                List.copyOf(repositories),
                List.copyOf(executed),
                List.copyOf(failures),
                List.copyOf(unsupported),
                staleExclusions);
    }

    private List<Class<?>> repositoryInterfaces() {
        return context.getBeansOfType(RepositoryFactoryInformation.class).values().stream()
                .<Class<?>>map(
                        information -> information.getRepositoryInformation().getRepositoryInterface())
                .filter(type -> type.getPackageName().startsWith(BASE_PACKAGE))
                .sorted(Comparator.comparing(Class::getName))
                .toList();
    }

    private static List<Method> declaredQueryMethods(Class<?> repositoryInterface) {
        return Arrays.stream(repositoryInterface.getDeclaredMethods())
                .filter(method ->
                        !method.isDefault() && !method.isSynthetic() && !Modifier.isStatic(method.getModifiers()))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /** 예외 없이 끝나면 null 을, 아니면 그 예외를 준다. 쿼리가 실패해도 트랜잭션은 되돌린다. */
    private Throwable invoke(Target target, Object[] arguments) {
        try {
            return transaction.execute(status -> {
                status.setRollbackOnly();
                try {
                    Object returned = target.method().invoke(target.proxy(), arguments);
                    if (returned instanceof BaseStream<?, ?> stream) {
                        stream.close();
                    }
                    return null;
                } catch (InvocationTargetException e) {
                    return e.getCause();
                } catch (IllegalAccessException | IllegalArgumentException e) {
                    return e;
                }
            });
        } catch (RuntimeException e) {
            // 트랜잭션을 열거나 되돌리는 일이 실패한 것도 그 메서드의 실패로 담는다.
            return e;
        }
    }

    private static Object[] arguments(Method method) {
        Type[] types = method.getGenericParameterTypes();
        Object[] arguments = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            arguments[i] = value(types[i]);
        }
        return arguments;
    }

    private static Object value(Type type) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            if (raw == Collection.class || raw == List.class) {
                return List.of(value(parameterized.getActualTypeArguments()[0]));
            }
            if (raw == Set.class) {
                return Set.of(value(parameterized.getActualTypeArguments()[0]));
            }
        }
        if (type instanceof Class<?> raw) {
            if (raw == Long.class || raw == long.class) {
                return 1L;
            }
            if (raw == Integer.class || raw == int.class) {
                return 1;
            }
            if (raw == Boolean.class || raw == boolean.class) {
                return false;
            }
            if (raw == String.class) {
                return "x";
            }
            if (raw == Instant.class) {
                return FIXED_INSTANT;
            }
            if (raw == UUID.class) {
                return FIXED_UUID;
            }
            if (raw == Pageable.class) {
                return PageRequest.of(0, 1);
            }
            if (raw.isEnum() && raw.getEnumConstants().length > 0) {
                return raw.getEnumConstants()[0];
            }
            if (raw.isRecord()) {
                return record(raw);
            }
        }
        throw new UnsupportedTypeException(type.getTypeName());
    }

    private static Object record(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] componentTypes = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            componentTypes[i] = components[i].getType();
            values[i] = value(components[i].getGenericType());
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(componentTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getName() + " 의 정식 생성자를 부르지 못했다", e);
        }
    }

    private record Target(Object proxy, Method method) {}

    /** 인자 값을 만드는 규칙에 없는 타입이다. 메시지는 그 타입의 이름이다. */
    private static final class UnsupportedTypeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnsupportedTypeException(String typeName) {
            super(typeName);
        }
    }
}
