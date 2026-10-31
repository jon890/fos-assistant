package com.bifos.assistant.testsupport;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * MySQL 검사 클래스에서 {@link MysqlCase} 가 없는 상위 클래스 메서드를 건너뛴다.
 *
 * <p>JUnit 은 상위 클래스의 검사를 하위 클래스에서 모두 다시 돈다. H2 에서 이미 도는 검사를 MySQL 에서 통째로 되풀이하면
 * MySQL 이 H2 와 다른 부분만 보는 것이 아니라 전체 검사 시간만 늘어난다. 하위 클래스가 직접 선언한 메서드와
 * {@link MysqlCase} 를 단 메서드(또는 그 메서드를 선언한 클래스)만 돌린다.
 */
public class MysqlCasesOnly implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        AnnotatedElement element = context.getElement().orElse(null);
        if (!(element instanceof Method method)) {
            return ConditionEvaluationResult.enabled("메서드가 아니다");
        }
        Class<?> testClass = context.getRequiredTestClass();
        Class<?> declaring = method.getDeclaringClass();
        if (declaring.equals(testClass)
            || method.isAnnotationPresent(MysqlCase.class)
            || declaring.isAnnotationPresent(MysqlCase.class)) {
            return ConditionEvaluationResult.enabled("MySQL 에서 돌릴 이유가 있는 검사다");
        }
        return ConditionEvaluationResult.disabled(
            "H2 에서 도는 검사다. MySQL 에서 다시 돌리려면 @MysqlCase 로 이유를 단다: " + method.getName());
    }
}
