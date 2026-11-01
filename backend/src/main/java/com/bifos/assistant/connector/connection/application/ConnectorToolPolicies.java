package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.connector.connection.application.dto.ConnectorToolSummary;
import com.bifos.assistant.connector.connection.domain.ToolPolicy;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
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

    /** {@code assistant.json} 이 없는 표준 플러그인의 schema 값이다. 도구를 선언하지 않고 확인 도구가 없다. */
    public static final int STANDARD_SCHEMA = 3;


    /**
     * 선언하지 않은 도구를 {@code schema: 3} 처럼 다루는가. 표준 플러그인이거나, 도구를 선언하는 {@code schema: 2} 가
     * {@code default_tool_policy: ask} 로 선언했을 때 참이다. 칸이 없는 카탈로그는 거짓이라 선언하지 않은 도구를 거절한다.
     */
    public static boolean undeclaredAsk(ConnectorManifest manifest) {
        return manifest.schema() == STANDARD_SCHEMA || partialAsk(manifest);
    }


    /** 일부 도구만 선언하고({@code schema: 2}) 나머지를 호출마다 묻도록({@code default_tool_policy: ask}) 선언한 manifest 인가. */
    public static boolean partialAsk(ConnectorManifest manifest) {
        return manifest.schema() == DECLARING_SCHEMA
            && ConnectorManifest.DEFAULT_TOOL_POLICY_ASK.equals(manifest.defaultToolPolicy());
    }


    /**
     * 판정({@code ToolPolicyDecision.decide})에 넘기는 schema 다. 선언하지 않은 도구를 호출마다 묻는 manifest 는 표준 플러그인과
     * 같은 {@link #STANDARD_SCHEMA} 로 넘겨 같은 규칙을 쓴다. 선언한 도구는 schema 와 상관없이 선언으로 판정한다.
     */
    public static int decisionSchema(ConnectorManifest manifest) {
        return undeclaredAsk(manifest) ? STANDARD_SCHEMA : manifest.schema();
    }


    /** 이 manifest 의 도구 정책으로 판정해도 되는가. 거짓이면 그 커넥터를 없는 것으로 다룬다. */
    public static boolean valid(ConnectorManifest manifest) {
        if (manifest.schema() == STANDARD_SCHEMA) {
            // 선언이 없는 schema 다. 도구 선언이나 확인 도구, 선택지 도구가 실려 오면 대시보드가 다른 schema 로 읽은 것이다.
            return manifest.tools().isEmpty()
                && manifest.verifyTool() == null
                && manifest.fields().stream().allMatch(field -> field.options() == null);
        }
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
                    tool.name(), policy.title(), policy.risk(), policy.approval(), policy.grantable(), tool.summary()))
                .ifPresent(summaries::add);
        }
        return List.copyOf(summaries);
    }


    /**
     * MCP 서버가 낸 도구 이름 가운데 manifest 가 선언하지 않은 수다. 도구를 선언하지 않는 판은 세지 않는다.
     * {@code default_tool_policy: ask} 는 선언하지 않은 도구를 거절하지 않고 호출마다 물으므로 세지 않는다.
     */
    static int undeclared(ConnectorManifest manifest, List<String> toolNames) {
        if (manifest.schema() != DECLARING_SCHEMA || partialAsk(manifest)) {
            return 0;
        }
        Set<String> declared = manifest.tools().stream().map(ConnectorTool::name).collect(Collectors.toSet());
        return (int)toolNames.stream().filter(name -> !declared.contains(name)).count();
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
     *
     * <p>대화 허락은 승인을 받아 실행하고 밖으로 나가지 않으며 {@code conversation_grant} 를 참으로 선언한 도구에만 줄 수
     * 있다(ADR-20261012 / conversation-grant). 그 칸을 내지 않는 옛 대시보드 plugin 이나 선언하지 않은 도구는 닫힌 것으로 읽는다.
     * plugin 이 같은 조건을 먼저 검사하고, 여기서 밖으로 나가는 도구를 한 번 더 거른다.
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
                    : Optional.of(policy(tool, risk, approval));
            }
        }
        return Optional.empty();
    }


    private static ToolPolicy policy(ConnectorTool tool, ToolRisk risk, ToolApproval approval) {
        boolean required = approval == ToolApproval.REQUIRED;
        boolean grantable = required && !Boolean.FALSE.equals(tool.grant());
        boolean conversationGrantable = required
            && !Boolean.TRUE.equals(tool.outbound())
            && Boolean.TRUE.equals(tool.conversationGrant());
        return new ToolPolicy(
            risk,
            approval,
            tool.title(),
            grantable,
            required ? Set.copyOf(tool.identifiers()) : Set.of(),
            conversationGrantable);
    }
}
