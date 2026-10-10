package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorApprovedExecution;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 대시보드 plugin 의 커넥터 경로를 부른다.
 *
 * <p>커넥터의 이름과 env 이름과 서버 이름을 갖지 않는다. 모두 호출하는 쪽이 카탈로그에서 꺼내 준다(ADR-043).
 * 응답 모양이 틀리거나 호출이 실패하면 원문 없는 {@link IllegalStateException} 을 던진다.
 */
public interface HermesConnectorClient {
    /** 바인딩 설치의 방식 이름이다. 소유 기록의 {@code mode} 와 같다. */
    String MODE_BIND = "bind";

    /** 커넥터 전용 profile 에 한 옛 설치의 방식 이름이다. 대시보드가 방식을 내지 않으면 이것으로 읽는다. */
    String MODE_ISOLATED = "isolated";

    /**
     * 그 profile 의 설치 상태다.
     *
     * @param policyHook 그 profile 의 도구 호출이 정책 판정을 거치는가. 대시보드가 참이라고 답했을 때만 참이다
     * @param mode 설치 방식. {@link #MODE_BIND} 나 {@link #MODE_ISOLATED} 다. 설치하지 않았거나 옛 대시보드 plugin 이
     *     내지 않았으면 {@link #MODE_ISOLATED} 다
     */
    record ConnectorState(
            String profile,
            boolean enabled,
            boolean configured,
            boolean restartRequired,
            boolean policyHook,
            String mode,
            JsonNode executionGuard) {
        /** 기존 상태 생성자는 보호 지원이 없는 상태다. */
        public ConnectorState(
                String profile,
                boolean enabled,
                boolean configured,
                boolean restartRequired,
                boolean policyHook,
                String mode) {
            this(profile, enabled, configured, restartRequired, policyHook, mode, null);
        }
    }

    /**
     * 설치 요청의 결과다.
     *
     * @param restartRequired 대시보드가 답한 재시작 필요. 설치된 연결에는 늘 참이다
     * @param pluginUpdated 설치가 그 profile 의 hook plugin 파일을 바꿨는가. 떠 있는 gateway 가 옛 코드를 쥐고 있을
     *     수 있다는 뜻이다. 옛 대시보드 plugin 은 이 칸을 내지 않고, 없으면 거짓이다
     * @param reloadPending 바인딩 설치가 재시작 없이 공유 gateway 의 MCP 설정 맞추기 주기에 반영될 것을 바꿨는가
     *     (ADR-20261007 / connector-live-reload). 옛 대시보드 plugin 은 이 칸을 내지 않고, 없으면 거짓이다
     */
    record InstallResult(boolean restartRequired, boolean pluginUpdated, boolean reloadPending) {

        /** 반영 예정을 모르는 설치 결과다. {@code reloadPending} 은 거짓이다. */
        public InstallResult(boolean restartRequired, boolean pluginUpdated) {
            this(restartRequired, pluginUpdated, false);
        }
    }

    record ProbeResult(boolean ok, List<String> tools) {}

    /** 운영 목록에 있고 검증을 통과한 커넥터의 manifest 다. 요청마다 대시보드에서 읽는다. */
    List<ConnectorManifest> readCatalog();

    /**
     * 후보 값으로 선택지 도구나 확인 도구를 한 번 부른다. 대시보드는 값을 저장하지 않는다.
     *
     * @param ownerBrowser 요청자의 호출 표식을 실은 브라우저 중계 주소(ADR-20261008 / browser-gateway-token). null 이면 본문에
     *     키를 싣지 않는다. 사용자 브라우저를 쓰는 커넥터에만 넘긴다
     */
    CallResult call(String connectorId, String tool, Map<String, String> values, String ownerBrowser);

    /**
     * 보관 파일의 값으로 선택지 도구나 확인 도구를 한 번 부른다. 값은 Control Plane 을 거치지 않는다.
     *
     * @param ownerBrowser {@link #call} 과 같다
     */
    CallResult callWithVault(String connectorId, String tool, String vault, String ownerBrowser);

    /**
     * 연결의 칸 값을 보관 파일에 쓴다. 같은 이름의 보관 파일이 있으면 바꾼다.
     *
     * @param values 칸 key 를 키로 한 값. 칸이 없는 커넥터는 빈 값이다
     */
    void putVault(String vault, String connectorId, Map<String, String> values);

    /**
     * 보관 파일을 지운다.
     *
     * @return 지운 것이 있었는가. 없었으면 거짓이다
     */
    boolean deleteVault(String vault);

    /** 그 커넥터를 옛 설치한 profile 의 {@code .env} 에서 칸 값을 읽어 보관 파일에 쓴다. */
    void importVault(String vault, String connectorId, String profile);

    /**
     * 승인한 호출을 한 번 실행한다. 결과를 알 수 없으면 {@link ConnectorExecutionUnknown} 을 던진다.
     *
     * <p>실행되지 않은 것이 분명한 거절은 실패 결과로 돌려준다. 받은 쪽은 어느 경우에도 다시 부르지 않는다.
     *
     * @param hermesTool 실행할 도구의 등록 이름
     * @param argsJson 승인한 인자의 JSON object 글. 값이 같은 JSON 으로 다시 써서 보낸다
     */
    CallResult execute(String profile, String connectorId, String hermesTool, String argsJson);

    /** 읽기 전용 금융 준비다. 옛 구현의 미지원은 금융 차단으로 읽는다. */
    default CallResult prepare(String profile, String connectorId, String hermesTool, String argsJson) {
        return CallResult.failure(ConnectorCallError.UNAVAILABLE);
    }

    /** 원문과 일회성 권한을 단 한 번 전송한다. 기존 execute로 우회하지 않는다. */
    default CallResult executeApproved(
            String profile, String connectorId, String hermesTool, ConnectorApprovedExecution execution) {
        return CallResult.failure(ConnectorCallError.UNAVAILABLE);
    }

    /**
     * 커넥터 전용 profile 에 옛 방식으로 설치하거나 끈다. 바인딩 칸 없이 보낸다.
     *
     * <p>설치하는 사진 도구도 신뢰한 에이전트 주인의 격리 실행 공간을 쓴다(ADR-091). 켜는 요청은 보내기 전에 그 주인의 첨부
     * 디렉터리를 만들고, 만들지 못하면 {@link HermesRequestRejected} 를 던진다.
     */
    InstallResult putConnector(String profile, String connectorId, boolean enabled, String sandboxOwner);

    /**
     * 보관 파일의 값으로 그 profile 에 커넥터를 붙인다.
     *
     * <p>요청에 그 에이전트의 {@code sandboxOwner} 를 늘 싣는다. 사용자 첨부를 읽는 커넥터는 대시보드가 그 주인의 첨부 디렉터리를
     * 서버 정의에 넣는다(ADR-20261007 connector-owner-attachments). 보내기 전에 그 주인의 첨부 디렉터리를 최선 노력으로 만든다.
     * 만들지 못해도 경고만 남기고 요청을 보낸다. 첨부를 선언한 커넥터면 대시보드가 디렉터리를 확인하지 못해 409 로 거절한다.
     *
     * @param sandboxOwner 그 에이전트의 실행 공간 주인. {@code Agent#sandboxOwner()} 의 값이다
     * @param ownerBrowser 그 바인딩의 표식을 실은 브라우저 중계 주소(ADR-20261008 / browser-gateway-token). 중계가 꺼졌으면 빈 값이다.
     *     null 이면 본문에 키를 싣지 않는다. 사용자 브라우저를 쓰는 커넥터에만 넘긴다
     * @throws ConnectorInstallConflict 대시보드가 409 로 답했을 때. 그 profile 의 설정과 충돌한다
     * @throws ConnectorSandboxUnavailable 대시보드가 409 {@code sandbox_unavailable} 로 답했을 때. 그 profile 에 실행 공간이 없거나
     *     첨부 디렉터리를 확인하지 못했다
     * @throws ConnectorProfileRejected 대시보드가 401 로 답했을 때. 그 profile 이 커넥터를 받지 않는다
     */
    InstallResult bindConnector(
            String profile, String connectorId, String vault, String sandboxOwner, String ownerBrowser);

    /** 비밀이 아닌 guard 맥락을 싣는다. 옛 요청의 본문과 시그니처는 유지한다. */
    default InstallResult bindConnector(
            String profile,
            String connectorId,
            String vault,
            String sandboxOwner,
            String ownerBrowser,
            JsonNode guard) {
        if (guard != null) {
            throw new IllegalStateException();
        }
        return bindConnector(profile, connectorId, vault, sandboxOwner, ownerBrowser);
    }

    /**
     * 그 profile 에 붙인 커넥터를 뗀다. 요청은 옛 설치를 끄는 것과 같다. 대시보드가 소유 기록의 방식으로 떼는 법을 고른다.
     *
     * @throws ConnectorInstallConflict 대시보드가 409 로 답했을 때
     * @throws ConnectorProfileRejected 대시보드가 401 로 답했을 때
     */
    InstallResult unbindConnector(String profile, String connectorId);

    /** 그 profile 의 설치 상태다. 대시보드의 목록에 그 커넥터가 없으면 설치되지 않은 것으로 돌려준다. */
    ConnectorState readConnector(String profile, String connectorId);

    ProbeResult probe(String profile, String mcpServer);

    boolean putEnv(String profile, String key, String value);

    boolean deleteEnv(String profile, String key);
}
