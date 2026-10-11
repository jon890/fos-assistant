package com.bifos.assistant.connector.application;

import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.equal;
import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.keys;
import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.require;
import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.text;

import com.bifos.assistant.shared.util.Sha256;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 조건주문의 모델·실행·표시와 공식 원주문 투영을 검증한다. 금융 권한과 저장은 맡지 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorConditionalSnapshot {
    private static final Set<String> SUMMARY = Set.of(("orderKind account operation conditionalOrderId symbol market currency "
        + "type quantity orderType expireDate first second original normalization").split(" "));
    private static final Set<String> WRITE = Set.of("type", "quantity", "orderType", "expireDate", "first");
    private static final Set<String> DETAIL = Set.of("conditionalOrderId", "type", "status", "symbol", "market", "quantity", "orderType", "first", "createdAt");
    private static final Set<String> STATUS = Set.of("WATCHING", "PAUSED", "ORDERING", "ORDERED", "COMPLETED", "EXPIRED");
    private static final List<String> NORMALIZED = List.of("symbol", "quantity", "first.triggerPrice", "first.orderPrice", "second.triggerPrice", "second.orderPrice");

    /** 정확한 키 집합과 의미 보존을 함께 판정한다. */
    static void validate(JsonNode input, JsonNode args, JsonNode summary, JsonNode scope, String operation) {
        keys(summary, SUMMARY);
        keys(scope, Set.of("account_seq"));
        require("CONDITIONAL".equals(text(summary.get("orderKind"), 1, 16)));
        require(operation.equals(text(summary.get("operation"), 1, 6)));
        require(text(summary.get("account"), 4, 4).matches("[0-9]{4}"));
        String market = market(args);
        require(market.equals(text(summary.get("market"), 2, 2)));
        equal(args.get("currency"), summary.get("currency"));
        equal(args.get("account_seq"), scope.get("account_seq"));
        Set<String> model = new HashSet<>();
        Set<String> execution = new HashSet<>(Set.of("account_seq", "market", "currency"));
        if ("CREATE".equals(operation)) {
            model.add("symbol");
            execution.addAll(Set.of("symbol", "clientOrderId"));
            symbol(input.get("symbol"));
            symbol(args.get("symbol"));
            require(summary.get("conditionalOrderId").isNull() && summary.get("original").isNull());
            String uuid = text(args.get("clientOrderId"), 36, 36);
            require(UUID.fromString(uuid).toString().equals(uuid));
        } else {
            model.add("conditionalOrderId");
            execution.addAll(Set.of("conditionalOrderId", "expected_conditional_order"));
            id(input.get("conditionalOrderId"));
            equal(input.get("conditionalOrderId"), args.get("conditionalOrderId"));
            equal(summary.get("conditionalOrderId"), args.get("conditionalOrderId"));
            detail(summary.get("original"), market);
            equal(summary.get("original"), args.get("expected_conditional_order"));
            equal(summary.get("original").get("conditionalOrderId"), args.get("conditionalOrderId"));
        }
        if (!"CANCEL".equals(operation)) {
            model.addAll(WRITE);
            execution.addAll(WRITE);
            if (input.has("second")) {
                model.add("second");
                execution.add("second");
            }
            if (input.has("confirmHighValueOrder")) {
                model.add("confirmHighValueOrder");
                require(input.get("confirmHighValueOrder").isBoolean() && !input.get("confirmHighValueOrder").booleanValue());
            }
            write(input, market);
            write(args, market);
            normalization(input, args, summary.get("normalization"));
        } else {
            require(summary.get("normalization").isArray() && summary.get("normalization").isEmpty());
        }
        keys(input, model);
        keys(args, execution);
        display(args, summary, operation);
    }

    private static void display(JsonNode args, JsonNode summary, String operation) {
        JsonNode shown = "CANCEL".equals(operation) ? summary.get("original") : args;
        for (String field : Set.of("type", "quantity", "orderType", "expireDate", "first", "second")) {
            JsonNode value = shown.get(field);
            if (value == null) {
                require(summary.get(field).isNull());
            } else {
                equal(summary.get(field), value);
            }
        }
        JsonNode symbol = "CREATE".equals(operation) ? args.get("symbol") : summary.get("original").get("symbol");
        equal(summary.get("symbol"), symbol);
    }

    private static String market(JsonNode args) {
        String value = text(args.get("market"), 1, 16);
        boolean kr = Set.of("KOSPI", "KOSDAQ", "KR_ETC").contains(value);
        require(kr || Set.of("NYSE", "NASDAQ", "AMEX", "US_ETC").contains(value));
        require((kr ? "KRW" : "USD").equals(text(args.get("currency"), 3, 3)));
        return kr ? "KR" : "US";
    }

    private static void write(JsonNode node, String market) {
        String type = enumeration(node.get("type"), Set.of("SINGLE", "OCO", "OTO"));
        String orderType = enumeration(node.get("orderType"), Set.of("LIMIT", "MARKET"));
        decimal(node.get("quantity"));
        date(node.get("expireDate"));
        require(node.has("second") != "SINGLE".equals(type));
        require("SINGLE".equals(type) || "LIMIT".equals(orderType));
        for (String name : List.of("first", "second")) {
            if (!node.has(name)) {
                continue;
            }
            JsonNode leg = node.get(name);
            keys(leg, "LIMIT".equals(orderType) ? Set.of("orderSide", "triggerPrice", "orderPrice") : Set.of("orderSide", "triggerPrice"));
            enumeration(leg.get("orderSide"), Set.of("BUY", "SELL"));
            decimal(leg.get("triggerPrice"));
            if (leg.has("orderPrice")) {
                String price = decimal(leg.get("orderPrice"));
                int scale = new BigDecimal(price).scale();
                require("KR".equals(market) ? scale <= 0 : scale <= (new BigDecimal(price).compareTo(BigDecimal.ONE) < 0 ? 4 : 2));
            }
        }
        if (!"SINGLE".equals(type)) {
            require("SELL".equals(node.get("second").get("orderSide").stringValue()));
            require(("OCO".equals(type) ? "SELL" : "BUY").equals(node.get("first").get("orderSide").stringValue()));
            if ("OCO".equals(type)) {
                require(new BigDecimal(node.get("first").get("triggerPrice").stringValue())
                    .compareTo(new BigDecimal(node.get("second").get("triggerPrice").stringValue())) > 0);
            }
        }
    }

    private static void detail(JsonNode node, String market) {
        optionalKeys(node, DETAIL, Set.of("expireDate", "second"));
        id(node.get("conditionalOrderId"));
        symbol(node.get("symbol"));
        require(market.equals(text(node.get("market"), 2, 2)));
        String type = enumeration(node.get("type"), Set.of("SINGLE", "OCO", "OTO"));
        enumeration(node.get("status"), STATUS);
        enumeration(node.get("orderType"), Set.of("LIMIT", "MARKET"));
        decimal(node.get("quantity"));
        if (node.has("expireDate")) {
            date(node.get("expireDate"));
        }
        String created = text(node.get("createdAt"), 1, 64);
        require(created.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})"));
        try {
            require(OffsetDateTime.parse(created).getYear() > 0);
        } catch (DateTimeException ignored) {
            require(false);
        }
        JsonNode second = node.get("second");
        require("SINGLE".equals(type) ? second == null || second.isNull() : second != null && second.isObject());
        detailLeg(node.get("first"));
        if (second != null && !second.isNull()) {
            detailLeg(second);
            equal(node.get("first").get("type"), second.get("type"));
        }
    }

    private static void detailLeg(JsonNode leg) {
        optionalKeys(leg, Set.of("type", "status", "orderSide"), Set.of("triggerPrice", "targetProfitRate", "orderPrice", "triggeredOrderId"));
        enumeration(leg.get("type"), Set.of("STOP", "PROFIT_RATE"));
        Set<String> status = new HashSet<>(STATUS);
        status.addAll(Set.of("HOLDING", "CANCELED"));
        enumeration(leg.get("status"), status);
        enumeration(leg.get("orderSide"), Set.of("BUY", "SELL"));
        for (String name : Set.of("triggerPrice", "targetProfitRate", "orderPrice")) {
            if (leg.has(name) && !leg.get(name).isNull()) {
                if ("targetProfitRate".equals(name)) {
                    require(text(leg.get(name), 1, 30).matches("-?[0-9]+(\\.[0-9]+)?"));
                } else {
                    decimal(leg.get(name));
                }
            }
        }
        if (leg.has("triggeredOrderId") && !leg.get("triggeredOrderId").isNull()) {
            id(leg.get("triggeredOrderId"));
        }
    }

    private static void normalization(JsonNode input, JsonNode args, JsonNode changes) {
        require(changes.isArray());
        int index = 0;
        for (String name : NORMALIZED) {
            JsonNode before = path(input, name);
            JsonNode after = path(args, name);
            if (before == null) {
                require(after == null);
                continue;
            }
            String canonical = "symbol".equals(name) ? text(before, 1, 32).toUpperCase(Locale.ROOT) : decimal(before);
            require(canonical.equals(text(after, 1, 32)));
            if (!before.equals(after)) {
                require(index < changes.size());
                JsonNode change = changes.get(index++);
                keys(change, Set.of("field", "before", "after"));
                require(name.equals(text(change.get("field"), 1, 32)));
                equal(before, change.get("before"));
                equal(after, change.get("after"));
            }
        }
        require(index == changes.size());
        for (String name : Set.of("type", "orderType", "expireDate", "conditionalOrderId", "first.orderSide", "second.orderSide")) {
            JsonNode before = path(input, name);
            JsonNode after = path(args, name);
            require(before == null ? after == null : before.equals(after));
        }
    }

    /** operation별 고정 경로를 UTF-8 길이 접두 표기로 인코딩한다. */
    static String requestKey(JsonNode args, Long userId, Long connectionId, String tool, String operation) {
        StringBuilder body = new StringBuilder(lp("fos-conditional-intent-v1"));
        String[] names = {"operation", "userId", "connectionId", "tool"};
        String[] values = {operation, userId.toString(), connectionId.toString(), tool};
        for (int i = 0; i < names.length; i++) {
            body.append(lp(names[i])).append('S').append(lp(values[i]));
        }
        for (String name : List.of("account_seq", "market", "currency")) {
            append(body, name, args);
        }
        append(body, "CREATE".equals(operation) ? "symbol" : "conditionalOrderId", args);
        if (!"CANCEL".equals(operation)) {
            for (String name : List.of("type", "quantity", "orderType", "expireDate", "first", "first.orderSide", "first.triggerPrice", "first.orderPrice",
                "second", "second.orderSide", "second.triggerPrice", "second.orderPrice")) {
                append(body, name, args);
            }
        }
        return Sha256.hex(body.toString());
    }

    private static void append(StringBuilder body, String name, JsonNode args) {
        JsonNode value = path(args, name);
        body.append(lp(name));
        if (value == null) {
            body.append('A');
        } else if (value.isNull()) {
            body.append('N');
        } else if (value.isObject()) {
            body.append('O');
        } else {
            body.append('S').append(lp(value.stringValue()));
        }
    }

    private static JsonNode path(JsonNode node, String name) {
        for (String part : name.split("\\.")) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(part);
        }
        return node;
    }

    private static String lp(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    private static void optionalKeys(JsonNode node, Set<String> required, Set<String> optional) {
        require(node != null && node.isObject() && node.propertyNames().containsAll(required));
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        require(allowed.containsAll(node.propertyNames()));
    }

    private static String enumeration(JsonNode node, Set<String> values) {
        String value = text(node, 1, 32);
        require(values.contains(value));
        return value;
    }

    private static String decimal(JsonNode node) {
        String value = text(node, 1, 30);
        require(value.matches("[0-9]+(\\.[0-9]+)?") && new BigDecimal(value).signum() > 0);
        return ConnectorNormalization.canonicalDecimal(value);
    }

    private static void symbol(JsonNode node) {
        require(text(node, 1, 32).matches("[A-Za-z0-9.-]{1,32}"));
    }

    private static void id(JsonNode node) {
        String value = text(node, 1, 256);
        require(!Set.of(".", "..").contains(value) && value.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
            && !value.contains("\uFEFF") && !value.matches(".*[/\\\\?#%:].*"));
    }

    private static void date(JsonNode node) {
        String value = text(node, 10, 10);
        require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        try {
            require(LocalDate.parse(value).getYear() > 0 && LocalDate.parse(value).toString().equals(value));
        } catch (DateTimeException ignored) {
            require(false);
        }
    }
}
