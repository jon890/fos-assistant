package com.bifos.assistant.connector.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 주문서 준비({@code CHECKOUT})의 준비 결과 검증과 보호 선언을 본다(ADR-20261113 / purchase-checkout).
 *
 * <p>사례 표 {@code test/resources/checkout-snapshot-v1.json} 은 대시보드의 {@code test_connector_checkout_validation.py} 가 같은 판정으로
 * 소비한다.
 */
class ConnectorCheckoutSnapshotTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Test
    @DisplayName("공통 사례 표의 통과와 거절이 대시보드와 같다")
    void sharedCases() throws Exception {
        JsonNode cases = JSON.readTree(Files.readString(Path.of("../test/resources/checkout-snapshot-v1.json"))).get("cases");
        int valid = 0;
        for (JsonNode item : cases) {
            String name = item.get("name").stringValue();
            String model = JSON.writeValueAsString(item.get("modelArgs"));
            String args = JSON.writeValueAsString(item.get("executionArgs"));
            String summary = JSON.writeValueAsString(item.get("summary"));
            if (item.get("valid").booleanValue()) {
                var snapshot = ConnectorExecutionSnapshot.validate(model, args, summary, "{}", "CREATE", ConnectorExecutionSnapshot.CHECKOUT);
                assertThat(snapshot.kind()).as(name).isEqualTo(ConnectorExecutionSnapshot.CHECKOUT);
                assertThat(snapshot.operation()).as(name).isEqualTo("CREATE");
                assertThat(snapshot.expectedTotalKrw()).as(name).hasValue(item.get("executionArgs").get("expectedTotalKrw").longValue());
                valid++;
            } else {
                assertThatThrownBy(() -> ConnectorExecutionSnapshot.validate(model, args, summary, "{}", "CREATE",
                    ConnectorExecutionSnapshot.CHECKOUT)).as(name).isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThat(valid).isPositive().isLessThan(cases.size());
    }


    @Test
    @DisplayName("주문서 준비는 scope 가 비어야 하고 만들기만 받으며 선언한 종류와 같아야 한다")
    void checkoutNeedsEmptyScopeCreateAndDeclaredKind() throws Exception {
        JsonNode base = JSON.readTree(Files.readString(Path.of("../test/resources/checkout-snapshot-v1.json"))).get("cases").get(0);
        String model = JSON.writeValueAsString(base.get("modelArgs"));
        String args = JSON.writeValueAsString(base.get("executionArgs"));
        String summary = JSON.writeValueAsString(base.get("summary"));
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.validate(model, args, summary, "{\"account_seq\":\"7\"}", "CREATE",
            ConnectorExecutionSnapshot.CHECKOUT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.validate(model, args, summary, "{}", "CANCEL",
            ConnectorExecutionSnapshot.CHECKOUT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.validate(model, args, summary, "{}", "CREATE",
            ConnectorExecutionSnapshot.ORDER)).isInstanceOf(IllegalArgumentException.class);
        var snapshot = ConnectorExecutionSnapshot.validate(model, args, summary, "{}", "CREATE", ConnectorExecutionSnapshot.CHECKOUT);
        snapshot.validateDeclaredScope("[]", Map.of());
        assertThatThrownBy(() -> snapshot.validateDeclaredScope("[{\"arg\":\"account_seq\",\"field\":\"account\"}]",
            Map.of("account", "7"))).isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    @DisplayName("같은 상품, 옵션, 수량은 금액이 달라도 같은 의도 키이고 수량이나 옵션이 다르면 다른 키다")
    void requestKeyIgnoresAmount() throws Exception {
        JsonNode base = JSON.readTree(Files.readString(Path.of("../test/resources/checkout-snapshot-v1.json"))).get("cases").get(0);
        String model = JSON.writeValueAsString(base.get("modelArgs"));
        ObjectNode args = (ObjectNode)base.get("executionArgs").deepCopy();
        ObjectNode summary = (ObjectNode)base.get("summary").deepCopy();
        String key = ConnectorExecutionSnapshot.validate(model, JSON.writeValueAsString(args), JSON.writeValueAsString(summary), "{}",
            "CREATE", ConnectorExecutionSnapshot.CHECKOUT).requestKey(1L, "naver-shopping", "fill_checkout");
        args.put("expectedTotalKrw", 24000);
        summary.put("shippingFeeKrw", 4000).put("expectedTotalKrw", 24000);
        String priced = ConnectorExecutionSnapshot.validate(model, JSON.writeValueAsString(args), JSON.writeValueAsString(summary), "{}",
            "CREATE", ConnectorExecutionSnapshot.CHECKOUT).requestKey(1L, "naver-shopping", "fill_checkout");
        assertThat(priced).isEqualTo(key);
        assertThat(ConnectorExecutionSnapshot.validate(model, JSON.writeValueAsString(args), JSON.writeValueAsString(summary), "{}",
            "CREATE", ConnectorExecutionSnapshot.CHECKOUT).requestKey(2L, "naver-shopping", "fill_checkout")).isNotEqualTo(key);
    }


    @Test
    @DisplayName("보호 선언은 CHECKOUT 도구가 PURCHASE/always 의 만들기이고 scope 가 빌 때만 받고 금융 도구와 섞지 않는다")
    void guardAcceptsOnlyPurchaseCheckout() {
        var guard = new ConnectorExecutionGuard();
        var contract = guard.read(manifest(node -> {
        }, "PURCHASE"));
        assertThat(contract.checkout()).isTrue();
        assertThat(contract.kind("fill_checkout")).isEqualTo(ConnectorExecutionSnapshot.CHECKOUT);
        List<Consumer<ObjectNode>> defects = List.of(
            node -> node.putArray("scope_fields").addObject().put("arg", "account_seq").put("field", "account"),
            node -> ((ObjectNode)node.get("operations")).put("fill_checkout", "CANCEL"),
            node -> ((ObjectNode)node.get("kinds")).put("fill_checkout", "ORDER"),
            node -> ((ObjectNode)node.get("operations")).put("create_order", "CREATE"));
        for (var defect : defects) {
            assertThatThrownBy(() -> guard.read(manifest(defect, "PURCHASE"))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> guard.read(manifest(node -> {
        }, "FINANCIAL"))).isInstanceOf(IllegalArgumentException.class);
    }


    private static ConnectorManifest manifest(Consumer<ObjectNode> change, String fillRisk) {
        ObjectNode node = JSON.createObjectNode().put("protocol", ConnectorExecutionSnapshot.PROTOCOL).put("prepare_tool", "prepare_checkout");
        node.putArray("scope_fields");
        node.putObject("operations").put("fill_checkout", "CREATE");
        node.putObject("kinds").put("fill_checkout", "CHECKOUT");
        change.accept(node);
        var tools = List.of(new ConnectorTool("prepare_checkout", "READ", "none", "주문서 준비", false),
            new ConnectorTool("fill_checkout", fillRisk, "always", "주문서 채우기", false),
            new ConnectorTool("create_order", "FINANCIAL", "always", "주문", false));
        return new ConnectorManifest("naver-shopping", "네이버 쇼핑", "", List.of(), null, "naver-shopping", 2, tools, List.of(), null,
            false, true, null, node, "0".repeat(64));
    }
}
