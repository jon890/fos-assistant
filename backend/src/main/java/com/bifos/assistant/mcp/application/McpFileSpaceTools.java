package com.bifos.assistant.mcp.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.artifact.application.ArtifactPutFiles;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactKept;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.port.SandboxFiles;
import com.bifos.assistant.mcp.application.port.SandboxFiles.SandboxFile;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트가 실행 공간이나 대화 폴더의 파일을 요청자의 「내 파일」 에 넣는 MCP 도구다(ADR-20261119 / file-space-put).
 *
 * <p>주인과 대화는 origin 실행에서만 정한다. 인자에 대화 식별자가 없다. 실행 공간은 origin 실행의 에이전트가 쓰는 공간이다.
 * 결과와 로그에 호스트 경로와 내부 번호를 담지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class McpFileSpaceTools {

    public static final String PUT = "file_space_put";
    private static final String WORKSPACE_PREFIX = "/workspace/";
    /** 자동 보관이 도는 동안 바로 거절되지 않게 보관 자리를 기다리는 시간이다. */
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ArtifactPutFiles putFiles;
    private final SandboxFiles sandboxFiles;
    private final AgentService agents;


    /** 넣지 못한 까닭이다. 도구 결과에 이름 그대로 나간다. */
    private enum Reason {
        UNSUPPORTED_TYPE("받지 않는 형식이거나 파일 내용이 형식과 맞지 않는다. 받는 형식은 그림, 문서(HTML, CSS, MD, TXT, PDF, DOCX, PPTX), 표(CSV, TSV, XLSX)다"),
        TOO_LARGE("파일이 25MB 상한을 넘는다"),
        QUOTA_FULL("사용자의 파일 공간 용량이 찼다. 사용자가 「내 파일」 에서 파일을 지워야 한다"),
        NOT_FOUND("파일을 찾지 못했다. 파일이 없거나 폴더, 링크이거나 경로가 틀렸다"),
        UNAVAILABLE("지금은 넣을 수 없다. 잠시 뒤 다시 한다");


        private final String text;


        Reason(String text) {
            this.text = text;
        }
    }


    public Map<String, Object> put(McpCaller caller, String path, String title, String description) {
        AgentExecution origin = caller.originExecution();
        Long conversationId = origin.conversationId();
        Long userId = caller.user().id();
        if (conversationId == null) {
            // 대화 밖 실행은 넣은 파일을 묶을 답이 없다. 다른 도구와 같은 맥락 오류로 답한다(설계의 「file_space_put 도구」).
            return McpToolService.result(McpToolService.INVALID_CONTEXT, true);
        }
        try {
            ArtifactKept kept = keep(origin, userId, conversationId, path, title, description);
            return McpToolService.result(success(kept), false);
        } catch (ApiException ex) {
            return failure(caller, reasonOf(ex));
        } catch (RuntimeException ex) {
            log.warn(
                "file space put failed userId={} executionId={} exceptionClass={}", userId, caller.executionId(),
                ex.getClass().getSimpleName());
            return failure(caller, Reason.UNAVAILABLE);
        }
    }


    /** 경로를 이 대화의 결과물 폴더, 실행 공간, 그 밖으로 나눠 넣는다. 판정 순서는 설계의 「{@code file_space_put} 도구」 다. */
    private ArtifactKept keep(
        AgentExecution origin, Long userId, Long conversationId, String path, String title, String description) {
        Optional<String> folderPath = putFiles.folderPathOf(conversationId, path);
        if (folderPath.isPresent()) {
            return putFiles.putFromFolder(userId, conversationId, folderPath.get(), title, description, WAIT);
        }
        if (!path.startsWith(WORKSPACE_PREFIX)) {
            throw notFound();
        }
        String relative = path.substring(WORKSPACE_PREFIX.length());
        Agent agent = Optional.ofNullable(origin.agentId()).flatMap(agents::findById).orElseThrow(McpFileSpaceTools::notFound);
        SandboxFile file = sandboxFiles.read(agent.sandboxOwner(), relative, putFiles.maxFileBytes());
        return switch (file.status()) {
            case FOUND -> putFiles.putFromWorkspace(userId, conversationId, relative, file.bytes(), title, description, WAIT);
            case TOO_LARGE -> throw new ApiException(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED, "this file is larger than the vault limit");
            case MISSING -> throw notFound();
        };
    }


    private static String success(ArtifactKept kept) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saved", true);
        body.put("already", kept.already());
        body.put("title", kept.item().title());
        body.put(
            "message",
            kept.already()
                ? "이미 사용자의 파일 공간(내 파일)에 있다. 다시 넣지 않았다. 사용자에게 이미 있다고 알린다."
                : "사용자의 파일 공간(내 파일)에 넣었다. 답 아래에도 파일이 보인다.");
        return JSON.writeValueAsString(body);
    }


    private static Map<String, Object> failure(McpCaller caller, Reason reason) {
        log.info(
            "file space put refused userId={} executionId={} reason={}", caller.user().id(), caller.executionId(), reason);
        return McpToolService.result(
            "파일 공간에 넣지 못했다. 까닭: " + reason.name() + ". " + reason.text + ". 사용자에게 넣었다고 말하지 않는다.", true);
    }


    /** 보관 서비스의 거절을 오류 코드와 고정 메시지로 나눈다. 메시지를 결과에 그대로 내보내지 않는다. */
    private static Reason reasonOf(ApiException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        return switch (ex.code()) {
            case ARTIFACT_VAULT_UNSUPPORTED -> {
                if (message.contains("larger") || message.contains("too many")) {
                    yield Reason.TOO_LARGE;
                }
                yield message.contains("missing") ? Reason.NOT_FOUND : Reason.UNSUPPORTED_TYPE;
            }
            case ARTIFACT_NOT_FOUND, ARTIFACT_GONE -> Reason.NOT_FOUND;
            case ARTIFACT_VAULT_FULL -> Reason.QUOTA_FULL;
            default -> Reason.UNAVAILABLE;
        };
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.ARTIFACT_NOT_FOUND, "this file does not exist");
    }


    public Map<String, Object> definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of("type", "string"));
        properties.put("title", Map.of("type", "string"));
        properties.put("description", Map.of("type", "string"));
        return Map.of(
            "name", PUT,
            "description",
            "사용자의 파일 공간(내 파일)에 파일을 넣는다. path 가 /workspace/ 로 시작하면 작업 폴더(실행 공간)의 파일이고, 상대 경로나 "
                + "이 대화의 결과물 폴더 경로면 그 폴더의 파일이다. 받는 형식은 그림(png, jpg, jpeg, gif, webp), 문서(html, css, md, txt, pdf, "
                + "docx, pptx), 표(csv, tsv, xlsx)이고 파일 하나는 25MB 까지다. title 에 짧은 한국어 제목, description 에 한 줄 설명을 붙인다. "
                + "답에 묶인 HTML 과 image_generate 로 만든 그림은 자동으로 들어가므로 넣지 않는다.",
            "inputSchema",
            Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", properties,
                "required", List.of("path")));
    }
}
