package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TopLevelPackageOrderTest {

    @Test
    @DisplayName("위 패키지가 아래 패키지를 쓰면 위반이 없다")
    void allowsUpperPackageToUseLowerPackages() {
        assertThat(TopLevelPackageOrder.violations("chat", List.of("usage", "agent", "hermes")))
                .isEmpty();
    }

    @Test
    @DisplayName("아래 패키지가 위 패키지를 쓰면 그 간선을 위반으로 낸다")
    void reportsEdgeFromLowerToUpperPackage() {
        assertThat(TopLevelPackageOrder.violations("usage", List.of("chat")))
                .containsExactly("usage -> chat 는 층 순서를 거스른다");
    }

    @Test
    @DisplayName("위반이 여럿이면 도착 패키지의 이름 순으로 낸다")
    void reportsViolationsInTargetNameOrder() {
        assertThat(TopLevelPackageOrder.violations("hermes", List.of("user", "chat")))
                .containsExactly("hermes -> chat 는 층 순서를 거스른다", "hermes -> user 는 층 순서를 거스른다");
    }

    @Test
    @DisplayName("순서에 없는 도착 패키지는 건너뛴다")
    void skipsTargetMissingFromOrder() {
        assertThat(TopLevelPackageOrder.violations("usage", List.of("billing"))).isEmpty();
    }

    @Test
    @DisplayName("순서에 없는 패키지는 순서에 없다는 한 줄만 낸다")
    void reportsPackageMissingFromOrder() {
        assertThat(TopLevelPackageOrder.violations("billing", List.of("user"))).containsExactly("billing 는 층 순서에 없다");
    }

    @Test
    @DisplayName("맨 아래 패키지가 아무것도 쓰지 않으면 위반이 없다")
    void allowsBottomPackageWithoutDependencies() {
        assertThat(TopLevelPackageOrder.violations("hermes", List.of())).isEmpty();
    }

    @Test
    @DisplayName("맨 위 패키지는 나머지를 모두 써도 위반이 없다")
    void allowsTopPackageToUseEveryOtherPackage() {
        List<String> others = TopLevelPackageOrder.ORDER.subList(0, TopLevelPackageOrder.ORDER.size() - 1);

        assertThat(others).hasSize(16);
        assertThat(TopLevelPackageOrder.violations("attention", others)).isEmpty();
    }

    @Test
    @DisplayName("층 순서는 겹치는 이름 없이 열일곱이다")
    void orderHasSeventeenDistinctPackages() {
        assertThat(TopLevelPackageOrder.ORDER).hasSize(17);
        assertThat(new HashSet<>(TopLevelPackageOrder.ORDER)).hasSize(17);
    }
}
