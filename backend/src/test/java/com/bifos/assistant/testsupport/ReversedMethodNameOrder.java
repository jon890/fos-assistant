package com.bifos.assistant.testsupport;

import java.util.Comparator;
import org.junit.jupiter.api.MethodDescriptor;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;

/**
 * 검사 메서드를 이름의 역순으로 돌린다.
 *
 * <p>클래스가 프로세스나 대역을 함께 쓸 때, 앞 검사가 남긴 상태가 다음 검사를 바꾸지 않는지 순서를 뒤집어 확인하는 데 쓴다.
 * {@link MethodOrderer.MethodName} 으로 한 번, 이 순서로 한 번 돌리면 이웃한 두 검사가 양쪽 순서로 한 번씩 이어진다.
 * {@code @TestMethodOrder} 를 단 클래스는 그 순서를 따른다. 설정 값 {@code junit.jupiter.testmethod.order.default} 에 이 클래스의
 * 이름을 주어 켠다.
 */
public final class ReversedMethodNameOrder implements MethodOrderer {
    @Override
    public void orderMethods(MethodOrdererContext context) {
        context.getMethodDescriptors().sort(Comparator.comparing((MethodDescriptor descriptor) -> descriptor.getMethod().getName())
            .thenComparing(descriptor -> descriptor.getMethod().toString()).reversed());
    }
}
