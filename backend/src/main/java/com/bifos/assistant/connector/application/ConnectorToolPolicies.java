package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.ConnectorToolSummary;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * manifest 의 도구 정책을 읽고 하한을 검사한다(ADR-049).
 *
 * <p>대시보드가 같은 검사를 먼저 한다. 여기서 한 번 더 보는 것은 대시보드 plugin 이 옛 판이거나 고쳐졌어도 느슨한
 * 정책으로 판정하지 않기 위해서다. 규칙은 {@code docs/features/connector-policy.md} 의 「도구 정책」 이 갖는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorToolPolicies {
    /** 도구마다 정책을 선언하는 manifest 판이다. 그 앞의 판은 도구를 선언하지 않는다. */
    private static final int DECLARING_SCHEMA = 2;

    private static final int LEGACY_SCHEMA = 1;

    /** 이 manifest 의 도구 정책으로 판정해도 되는가. 거짓이면 그 커넥터를 없는 것으로 다룬다. */
    public static boolean valid(ConnectorManifest manifest) {
        if (manifest.schema() != LEGACY_SCHEMA && manifest.schema() != DECLARING_SCHEMA) {
            return false;
        }
        for (ConnectorTool tool : manifest.tools()) {
            if (policy(tool).isEmpty()) {
                return false;
            }
        }
        if (manifest.schema() != DECLARING_SCHEMA) {
            return true;
        }
        if (manifest.tools().isEmpty() || !readWithoutApproval(manifest, manifest.verifyTool())) {
            return false;
        }
        for (ConnectorField field : manifest.fields()) {
            if (field.options() != null
                    && !readWithoutApproval(manifest, field.options().tool())) {
                return false;
            }
        }
        return true;
    }

    /** 이름이 같은 도구의 선언이다. 선언이 없거나 읽을 수 없거나 이름이 null 이면 빈 값이다. */
    public static Optional<ToolPolicy> find(ConnectorManifest manifest, String toolName) {
        if (toolName == null) {
            return Optional.empty();
        }
        return manifest.tools().stream()
                .filter(tool -> toolName.equals(tool.name()))
                .findFirst()
                .flatMap(ConnectorToolPolicies::policy);
    }

    /** 화면에 낼 도구 선언이다. 도구를 선언하지 않는 판은 대시보드가 부르는 도구만 담고 있어 내지 않는다. */
    static List<ConnectorToolSummary> summaries(ConnectorManifest manifest) {
        if (manifest.schema() != DECLARING_SCHEMA) {
            return List.of();
        }
        List<ConnectorToolSummary> summaries = new ArrayList<>();
        for (ConnectorTool tool : manifest.tools()) {
            policy(tool)
                    .map(policy -> new ConnectorToolSummary(
                            tool.name(), policy.title(), policy.risk(), policy.approval(), policy.grantable()))
                    .ifPresent(summaries::add);
        }
        return List.copyOf(summaries);
    }

    /** MCP 서버가 낸 도구 이름 가운데 manifest 가 선언하지 않은 수다. 도구를 선언하지 않는 판은 세지 않는다. */
    static int undeclared(ConnectorManifest manifest, List<String> toolNames) {
        if (manifest.schema() != DECLARING_SCHEMA) {
            return 0;
        }
        Set<String> declared =
                manifest.tools().stream().map(ConnectorTool::name).collect(Collectors.toSet());
        return (int) toolNames.stream().filter(name -> !declared.contains(name)).count();
    }

    /** 대시보드가 승인 없이 부르는 확인 도구와 선택지 도구는 읽기 전용이고 승인이 없어야 한다. */
    private static boolean readWithoutApproval(ConnectorManifest manifest, String toolName) {
        return find(manifest, toolName)
                .filter(policy -> policy.risk() == ToolRisk.READ && policy.approval() == ToolApproval.NONE)
                .isPresent();
    }

    /**
     * 글자로 받은 선언을 읽는다. 모르는 위험도나 승인 방식, 하한보다 느슨한 선언은 빈 값이다.
     *
     * <p>상시 허락은 승인을 받아 실행하는 도구에만 줄 수 있고, 선언이 닫았으면 줄 수 없다(ADR-065). 옛 대시보드
     * plugin 은 그 칸을 내지 않으므로 없는 것은 닫지 않은 것으로 읽는다. 식별자 인자도 승인 카드가 있는 도구에만
     * 둔다(ADR-089).
     */
    private static Optional<ToolPolicy> policy(ConnectorTool tool) {
        ToolApproval approval = ToolApproval.fromWord(tool.approval());
        if (approval == null) {
            return Optional.empty();
        }
        for (ToolRisk risk : ToolRisk.values()) {
            if (risk.name().equals(tool.risk())) {
                return approval.looserThan(risk.floor())
                        ? Optional.empty()
                        : Optional.of(new ToolPolicy(
                                risk,
                                approval,
                                tool.title(),
                                approval == ToolApproval.REQUIRED && !Boolean.FALSE.equals(tool.grant()),
                                approval == ToolApproval.REQUIRED ? Set.copyOf(tool.identifiers()) : Set.of()));
            }
        }
        return Optional.empty();
    }
}
