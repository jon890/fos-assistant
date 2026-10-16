package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 패키지 배치 규칙마다 통과하는 소스와 위반하는 소스를 컴파일해 같은 규칙으로 돌린다. 메시지에는 어디에 두라는 안내가 있다. */
@Tag("architecture")
class PackageLayoutRulesTest {

    private static final String ROOT = "com.bifos.assistant.fixture";
    private static final Map<String, List<String>> ORDER = Map.of("shop", List.of("catalog", "order"));

    @TempDir
    Path temporary;

    private int compiled;


    /** 이름은 루트 아래의 상대 이름이다. {@code body} 는 package 줄 뒤의 본문이다. */
    private JavaClasses compile(String... namesAndBodies) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < namesAndBodies.length; i += 2) {
            String fqn = ROOT + "." + namesAndBodies[i];
            String packageName = fqn.substring(0, fqn.lastIndexOf('.'));
            sources.put(fqn, "package " + packageName + ";\n" + namesAndBodies[i + 1].replace("$ROOT", ROOT));
        }
        return ArchitectureRulesTest.compileFixture(temporary.resolve("fixture" + compiled++), sources);
    }


    private static String failure(ArchRule rule, JavaClasses fixture) {
        var result = rule.evaluate(fixture);
        assertThat(result.hasViolation()).as("위반이 있어야 한다").isTrue();
        return result.getFailureReport().toString();
    }


    @Test
    @DisplayName("application 직하의 빈은 허용하고 package-private 도우미와 설정 값도 건드리지 않는다")
    void acceptsBeansAtApplicationRoot() throws IOException {
        JavaClasses fixture = compile(
            "a.application.Svc", "@org.springframework.stereotype.Service public class Svc {}",
            "a.application.Helper", "class Helper {}",
            "a.application.Props", "@org.springframework.boot.context.properties.ConfigurationProperties(\"x\") public record Props(int n) {}");
        PackageLayoutRules.applicationRootHoldsOnlyBeans(ROOT).check(fixture);
    }


    @Test
    @DisplayName("application 직하의 인터페이스와 값과 public 비빈은 옮길 곳을 알려 준다")
    void rejectsNonBeansAtApplicationRoot() throws IOException {
        JavaClasses fixture = compile(
            "a.application.Port", "public interface Port {}",
            "a.application.Value", "public record Value(int n) {}",
            "a.application.Kind", "public enum Kind { A }",
            "a.application.Util", "public class Util {}",
            "chat.application.ChatPort", "public interface ChatPort {}");
        String report = failure(PackageLayoutRules.applicationRootHoldsOnlyBeans(ROOT), fixture);
        assertThat(report).contains(
            "fixture.a.application.Port 는 인터페이스다", "a.application.port 로 옮긴다",
            "fixture.a.application.Value 는 값이다", "a.application.dto 로 옮긴다",
            "fixture.a.application.Kind 는 값이다",
            "fixture.a.application.Util 는 public 클래스인데 Spring 빈이 아니다", "domain 으로 옮긴다",
            "chat.<하위 기능>.application.port 로 옮긴다");
    }


    @Test
    @DisplayName("application 아래 dto 와 port 만 허용하고 다른 하위 패키지는 패키지마다 한 줄로 알린다")
    void checksApplicationSubpackages() throws IOException {
        PackageLayoutRules.applicationSubpackagesAreDtoAndPort(ROOT).check(compile(
            "a.application.dto.V", "public record V() {}",
            "a.application.port.P", "public interface P {}"));
        JavaClasses fixture = compile(
            "a.application.model.A", "public record A() {}",
            "a.application.model.B", "public record B() {}",
            "a.application.toolset.C", "public class C {}");
        String report = failure(PackageLayoutRules.applicationSubpackagesAreDtoAndPort(ROOT), fixture);
        assertThat(report).contains(
            ROOT + ".a.application.model 는 application 의 하위 패키지로 허용되지 않는다",
            ROOT + ".a.application.toolset 는 application 의 하위 패키지로 허용되지 않는다",
            "model 은 dto 로");
        assertThat(report.split(ROOT + "\\.a\\.application\\.model 는", -1)).hasSize(2);
    }


    @Test
    @DisplayName("port 에는 인터페이스와 그 예외만 허용한다")
    void checksPortPackage() throws IOException {
        PackageLayoutRules.portPackageHoldsOnlyInterfaces(ROOT).check(compile(
            "a.application.port.P", "public interface P {}",
            "a.application.port.PFailure", "public class PFailure extends RuntimeException {}"));
        String report = failure(PackageLayoutRules.portPackageHoldsOnlyInterfaces(ROOT), compile(
            "a.application.port.Impl", "public class Impl {}"));
        assertThat(report).contains("fixture.a.application.port.Impl 는 port 패키지에 있지만 인터페이스가 아니다", "application.dto 로");
    }


    @Test
    @DisplayName("dto 에는 빈과 엔티티와 일반 인터페이스를 두지 않고 sealed 인터페이스는 허용한다")
    void checksDtoPackage() throws IOException {
        PackageLayoutRules.dtoPackageHoldsOnlyValues(ROOT).check(compile(
            "a.application.dto.V", "public record V() implements S {}",
            "a.application.dto.S", "public sealed interface S permits V {}",
            "a.application.dto.K", "public enum K { A }"));
        String report = failure(PackageLayoutRules.dtoPackageHoldsOnlyValues(ROOT), compile(
            "a.application.dto.Svc", "@org.springframework.stereotype.Service public class Svc {}",
            "a.application.dto.Open", "public interface Open {}",
            "a.application.dto.Row", "@jakarta.persistence.Entity public class Row {}"));
        assertThat(report).contains(
            "fixture.a.application.dto.Svc 는 dto 패키지에 있지만 값이 아니다",
            "fixture.a.application.dto.Open 는 dto 패키지에 있지만 값이 아니다",
            "fixture.a.application.dto.Row 는 dto 패키지에 있지만 값이 아니다",
            "application.port 로 옮긴다");
    }


    @Test
    @DisplayName("ConfigurationProperties 는 config 패키지에서만 허용한다")
    void checksConfigurationProperties() throws IOException {
        PackageLayoutRules.configurationPropertiesInConfig(ROOT).check(compile(
            "a.config.Props", "@org.springframework.boot.context.properties.ConfigurationProperties(\"x\") public record Props(int n) {}"));
        String report = failure(PackageLayoutRules.configurationPropertiesInConfig(ROOT), compile(
            "a.application.Props", "@org.springframework.boot.context.properties.ConfigurationProperties(\"x\") public record Props(int n) {}",
            "chat.infra.Other", "@org.springframework.boot.context.properties.ConfigurationProperties(\"y\") public record Other(int n) {}"));
        assertThat(report).contains(
            "fixture.a.application.Props 는 @ConfigurationProperties 인데 config 패키지 밖에 있다", "a.config 로 옮긴다",
            "chat.<하위 기능>.config 로 옮긴다");
    }


    @Test
    @DisplayName("층 규칙은 infra 가 port 와 dto 를 쓰는 것을 허용하고 application 의 빈을 쓰는 것을 막는다")
    void checksLayerDirection() throws IOException {
        ArchRule rule = PackageLayoutRules.layerDirection(ROOT);
        rule.check(compile(
            "a.presentation.C", "public class C { $ROOT.a.application.S s; $ROOT.a.application.dto.V v; }",
            "a.application.S", "public class S { $ROOT.a.application.port.P p; $ROOT.a.infra.I i; $ROOT.a.config.Cfg c; }",
            "a.application.port.P", "public interface P { $ROOT.a.application.dto.V get(); }",
            "a.application.dto.V", "public record V() {}",
            "a.infra.I", "public class I implements $ROOT.a.application.port.P { public $ROOT.a.application.dto.V get() { return null; } $ROOT.a.domain.D d; }",
            "a.domain.D", "public class D {}",
            "a.config.Cfg", "public class Cfg { $ROOT.a.domain.D d; }"));
        String report = failure(rule, compile(
            "a.application.S", "public class S {}",
            "a.infra.I", "public class I { $ROOT.a.application.S s; }",
            "a.domain.D", "public class D { $ROOT.a.application.dto.V v; }",
            "a.application.dto.V", "public record V() {}"));
        assertThat(report).contains("fixture.a.infra.I", "fixture.a.application.S", "fixture.a.domain.D", "층은 presentation");
    }


    @Test
    @DisplayName("application 직하의 클래스가 상한을 넘으면 그 패키지를 알린다")
    void checksApplicationPackageSize() throws IOException {
        String[] atLimit = new String[PackageLayoutRules.APPLICATION_SIZE_LIMIT * 2];
        String[] overLimit = new String[(PackageLayoutRules.APPLICATION_SIZE_LIMIT + 1) * 2];
        for (int i = 0; i < overLimit.length / 2; i++) {
            overLimit[i * 2] = "big.application.C" + i;
            overLimit[i * 2 + 1] = "public class C" + i + " {}";
            if (i < atLimit.length / 2) {
                atLimit[i * 2] = overLimit[i * 2];
                atLimit[i * 2 + 1] = overLimit[i * 2 + 1];
            }
        }
        PackageLayoutRules.applicationPackageSize(ROOT).check(compile(atLimit));
        String report = failure(PackageLayoutRules.applicationPackageSize(ROOT), compile(overLimit));
        assertThat(report).contains(ROOT + ".big.application 는 application 직하의 클래스가 상한(25)을 넘었다", "하위 기능으로 나눈다");
    }


    @Test
    @DisplayName("하위 기능은 아래쪽만 쓰고 위쪽이나 순서에 없는 하위 기능은 알린다")
    void checksSubFeatureOrder() throws IOException {
        PackageLayoutRules.subFeaturesFollowOrder(ROOT, ORDER).check(compile(
            "shop.order.application.OrderService", "public class OrderService { $ROOT.shop.catalog.application.CatalogService c; }",
            "shop.catalog.application.CatalogService", "public class CatalogService {}",
            "other.application.X", "public class X { $ROOT.shop.order.application.OrderService s; }"));
        String report = failure(PackageLayoutRules.subFeaturesFollowOrder(ROOT, ORDER), compile(
            "shop.order.application.OrderService", "public class OrderService {}",
            "shop.catalog.application.CatalogService", "public class CatalogService { $ROOT.shop.order.application.OrderService o; }",
            "shop.billing.application.Billing", "public class Billing {}"));
        assertThat(report).contains(
            "shop.catalog 의 " + ROOT + ".shop.catalog.application.CatalogService 가 위 하위 기능 shop.order 의 "
                + ROOT + ".shop.order.application.OrderService 를 쓴다",
            "순서: catalog, order", "application.port 에 인터페이스를 두고",
            "shop.billing 은 순서에 없다");
    }


    @Test
    @DisplayName("나눈 기능의 루트에 남은 역할 패키지는 패키지마다 한 줄로 알린다")
    void checksRootRolesOfSplitFeature() throws IOException {
        PackageLayoutRules.splitFeaturesHaveNoRootRoles(ROOT, ORDER).check(compile(
            "shop.order.application.S", "public class S {}",
            "plain.application.S", "public class S {}"));
        String report = failure(PackageLayoutRules.splitFeaturesHaveNoRootRoles(ROOT, ORDER), compile(
            "shop.application.model.A", "public record A() {}",
            "shop.application.model.B", "public record B() {}",
            "shop.domain.D", "public class D {}"));
        assertThat(report).contains(
            "shop.application.model 에 클래스가 남아 있다", "shop 는 하위 기능으로 나뉜 기능이라 루트에 역할 패키지를 두지 않는다",
            "shop.<하위 기능>.<역할> 로 옮긴다", "shop.domain 에 클래스가 남아 있다");
        assertThat(report.split("shop\\.application\\.model 에", -1)).hasSize(2);
    }


    @Test
    @DisplayName("운영 클래스의 단순 이름이 겹치면 두 전체 이름을 알린다")
    void checksUniqueSimpleNames() throws IOException {
        PackageLayoutRules.simpleClassNamesAreUnique(ROOT).check(compile(
            "a.application.One", "public class One { static class Inner {} }",
            "b.application.Two", "public class Two { static class Inner {} }"));
        String report = failure(PackageLayoutRules.simpleClassNamesAreUnique(ROOT), compile(
            "a.application.Same", "public class Same {}",
            "b.infra.Same", "public class Same {}"));
        assertThat(report).contains(
            "Same 가 " + ROOT + ".a.application.Same 와 " + ROOT + ".b.infra.Same 에 있다", "한쪽의 이름을 바꾼다");
    }


    @Test
    @DisplayName("admin 은 하위 기능 아래 application 과 dto 와 presentation 만 허용한다")
    void checksAdminShape() throws IOException {
        PackageLayoutRules.adminPackageShape(ROOT).check(compile(
            "admin.agent.application.S", "public class S {}",
            "admin.agent.application.dto.D", "public record D() {}",
            "admin.agent.presentation.C", "public class C {}",
            "a.infra.Store", "public class Store {}"));
        String report = failure(PackageLayoutRules.adminPackageShape(ROOT), compile(
            "admin.agent.infra.Store", "public class Store {}",
            "admin.agent.domain.Row", "public class Row {}",
            "admin.Loose", "public class Loose {}"));
        assertThat(report).contains(
            "fixture.admin.agent.infra.Store 는 admin 에 있지만 허용된 패키지가 아니다",
            "fixture.admin.agent.domain.Row", "fixture.admin.Loose", "엔티티와 저장소와 설정은 기능이 갖는다");
    }


    @Test
    @DisplayName("기능이 admin 을 쓰면 의존을 거꾸로 하라고 알린다")
    void checksFeaturesDoNotDependOnAdmin() throws IOException {
        PackageLayoutRules.featuresDoNotDependOnAdmin(ROOT).check(compile(
            "admin.agent.application.S", "public class S { $ROOT.a.application.Svc svc; }",
            "a.application.Svc", "public class Svc {}"));
        String report = failure(PackageLayoutRules.featuresDoNotDependOnAdmin(ROOT), compile(
            "admin.agent.application.S", "public class S {}",
            "a.application.Svc", "public class Svc { $ROOT.admin.agent.application.S s; }"));
        assertThat(report).contains(
            ROOT + ".a.application.Svc 가 admin 의 " + ROOT + ".admin.agent.application.S 를 쓴다",
            "기능은 admin 을 모른다", "application.dto 에 둔다");
    }


    @Test
    @DisplayName("admin 은 기능의 application 과 dto 와 domain.type 만 쓰고 그 밖은 두는 곳을 알린다")
    void checksAdminUsesOnlyFeatureApplication() throws IOException {
        PackageLayoutRules.adminUsesOnlyFeatureApplication(ROOT).check(compile(
            "admin.agent.application.S", "public class S { $ROOT.a.application.Svc s; $ROOT.a.application.dto.V v; $ROOT.a.domain.type.T t;"
                + " $ROOT.shared.util.U u; $ROOT.a.application.model.M m; }",
            "a.application.Svc", "public class Svc {}",
            "a.application.dto.V", "public record V() {}",
            "a.application.model.M", "public record M() {}",
            "a.domain.type.T", "public enum T { A }",
            "shared.util.U", "public class U {}"));
        String report = failure(PackageLayoutRules.adminUsesOnlyFeatureApplication(ROOT), compile(
            "admin.agent.presentation.C", "public class C { $ROOT.a.infra.Store store; $ROOT.a.domain.Row row; $ROOT.a.presentation.Dtos dtos; }",
            "a.infra.Store", "public class Store {}",
            "a.domain.Row", "public class Row {}",
            "a.presentation.Dtos", "public class Dtos {}"));
        assertThat(report).contains(
            ROOT + ".admin.agent.presentation.C 가 a 의 " + ROOT + ".a.infra.Store 를 쓴다",
            ROOT + ".a.domain.Row 를", ROOT + ".a.presentation.Dtos 를",
            "admin 은 기능의 application 과 application.dto 만 부른다", "응답 모양은 admin 의 *Dtos 에 둔다");
    }


    @Test
    @DisplayName("admin 의 엔드포인트는 requireAdmin 을 직접 또는 같은 클래스의 도우미로 부른다")
    void checksAdminEndpointsRequireAdmin() throws IOException {
        String provider = "public class CurrentUserProvider { public void requireAdmin() {} }";
        PackageLayoutRules.adminEndpointsRequireAdmin(ROOT).check(compile(
            "shared.auth.CurrentUserProvider", provider,
            "admin.agent.presentation.C", """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class C {
                    $ROOT.shared.auth.CurrentUserProvider users;
                    @GetMapping("/a") public String direct() { users.requireAdmin(); return ""; }
                    @GetMapping("/b") public String viaHelper() { admin(); return ""; }
                    private void admin() { users.requireAdmin(); }
                }
                """));
        String report = failure(PackageLayoutRules.adminEndpointsRequireAdmin(ROOT), compile(
            "shared.auth.CurrentUserProvider", provider,
            "admin.agent.presentation.C", """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class C {
                    @GetMapping("/a") public String open() { return ""; }
                }
                """));
        assertThat(report).contains(
            ROOT + ".admin.agent.presentation.C.open 가 requireAdmin 을 부르지 않는다", "첫 줄에서 requireAdmin 을 부른다");
    }


    @Test
    @DisplayName("requireAdmin 은 admin 과 shared.auth 밖에서 부르면 두는 곳을 알린다")
    void checksRequireAdminOnlyInAdmin() throws IOException {
        String provider = "public class CurrentUserProvider { public void requireAdmin() {} }";
        PackageLayoutRules.requireAdminOnlyInAdmin(ROOT).check(compile(
            "shared.auth.CurrentUserProvider", provider,
            "admin.agent.presentation.C", "public class C { $ROOT.shared.auth.CurrentUserProvider u; void m() { u.requireAdmin(); } }"));
        String report = failure(PackageLayoutRules.requireAdminOnlyInAdmin(ROOT), compile(
            "shared.auth.CurrentUserProvider", provider,
            "a.presentation.C", "public class C { $ROOT.shared.auth.CurrentUserProvider u; void list() { u.requireAdmin(); } }"));
        assertThat(report).contains(
            ROOT + ".a.presentation.C.list 가 CurrentUserProvider.requireAdmin 을 부른다",
            "admin.<하위 기능>.presentation 에 두고", "CurrentUser 의 역할로 판정한다");
    }
}
