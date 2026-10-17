package com.bifos.assistant.hermes.application.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 검증한 private snapshot 선언이다. 원문 JSON과 분리한 불변 값만 전달한다. */
public record ConnectorSnapshotDeclaration(String protocol, String compileTool, String startTool,
    String statusTool, Map<String, Boolean> capabilities) {
    public ConnectorSnapshotDeclaration {
        capabilities = Map.copyOf(capabilities);
        // 운영 선언의 활성화와 transport 구현은 별개다.
        if (!"content-snapshot-v1".equals(protocol) || !capabilities.keySet().equals(Set.of("compile", "start", "status"))
            || Set.copyOf(List.of(compileTool, startTool, statusTool)).size() != 3) {
            throw new IllegalStateException("snapshot 선언을 확인하지 못했습니다.");
        }
    }


    public static ConnectorSnapshotDeclaration read(JsonNode node, List<ConnectorTool> publicTools) {
        if (node == null) {
            return null;
        }
        if (!node.isObject() || !node.propertyNames().equals(Set.of("protocol", "compile_tool", "start_tool", "status_tool", "capabilities"))
            || !node.path("protocol").isString() || !"content-snapshot-v1".equals(node.path("protocol").stringValue())) {
            throw new IllegalStateException("snapshot 선언을 확인하지 못했습니다.");
        }
        var names = List.of(text(node, "compile_tool"), text(node, "start_tool"), text(node, "status_tool"));
        if (Set.copyOf(names).size() != 3 || publicTools.stream().anyMatch(tool -> names.contains(tool.name()))) {
            throw new IllegalStateException("snapshot 선언을 확인하지 못했습니다.");
        }
        requireCapabilities(node.path("capabilities"));
        return new ConnectorSnapshotDeclaration("content-snapshot-v1", names.get(0), names.get(1), names.get(2),
            Map.of("compile", true, "start", node.path("capabilities").path("start").booleanValue(),
                "status", node.path("capabilities").path("status").booleanValue()));
    }


    public static void requireCapabilities(JsonNode caps) {
        if (!caps.isObject() || !caps.propertyNames().equals(Set.of("compile", "start", "status"))
            || !caps.path("compile").isBoolean() || !caps.path("compile").booleanValue()
            || !caps.path("start").isBoolean() || !caps.path("status").isBoolean()
            || caps.path("start").booleanValue() != caps.path("status").booleanValue()) {
            throw new IllegalStateException("snapshot capability를 확인하지 못했습니다.");
        }
    }


    private static String text(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isString() || !value.stringValue().matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new IllegalStateException("snapshot 선언을 확인하지 못했습니다.");
        }
        return value.stringValue();
    }
}
