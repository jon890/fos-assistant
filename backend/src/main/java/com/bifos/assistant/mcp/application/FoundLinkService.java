package com.bifos.assistant.mcp.application;

import com.bifos.assistant.chat.conversation.application.FoundLinks;
import com.bifos.assistant.chat.conversation.application.dto.FoundPage;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * fos-ctx 가 웹 검색과 페이지 읽기의 결과에서 뽑아 알린 주소를 origin 실행의 대화에 남긴다(ADR-20261121 / found-link-image-source).
 *
 * <p><b>요청 본문이 사용자나 대화를 정하지 못한다.</b> 서명한 호출 맥락에서 origin 실행을 찾고 그 실행의 대화에만 남긴다.
 * 대화가 없는 실행이 알린 주소는 남기지 않는다. 주소를 로그에 적지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FoundLinkService {
    /** 호출 맥락을 서명할 때 넣는 도구 이름이다. fos-ctx 가 이 이름으로 맥락을 만든다. */
    public static final String TOOL = "found_links";
    /** 한 호출이 알릴 수 있는 후보의 수다. fos-ctx 의 {@code found_links.MAX_LINKS} 와 같다. */
    static final int MAX_LINKS = 2_000;
    /** 한 호출이 보낼 수 있는 페이지의 수다. 읽기 도구 한 번이 읽는 주소의 수보다 넉넉하다. */
    static final int MAX_PAGES = 200;
    /** 한 호출이 보낼 수 있는 「비서가 적어 넣은 주소」 의 수다. fos-ctx 의 {@code found_links.MAX_TYPED} 와 같다. */
    static final int MAX_TYPED = 50;
    /** 기준 주소와 적어 넣은 주소 하나의 길이 상한이다. */
    static final int MAX_LINK_CHARS = 2_048;

    private final McpCallerResolver callers;
    private final FoundLinks links;


    /**
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 는 맥락이 맞지 않을 때,
     * {@link ErrorCode#VALIDATION_FAILED} 는 본문 모양이 틀리거나 후보가 상한을 넘을 때다
     */
    public void record(McpPrincipal principal, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
        }
        McpCaller caller = callers.resolve(principal, TOOL, body.get("_fos_ctx"));
        List<FoundPage> pages = pages(body.get("pages"));
        List<String> typed = typed(body.get("typed"));
        AgentExecution origin = caller.originExecution();
        if (origin.conversationId() == null) {
            return;
        }
        int kept = links.record(origin.conversationId(), pages, typed);
        log.debug("찾은 주소를 남겼다 executionId={} hashes={}", origin.id(), kept);
    }


    private static List<FoundPage> pages(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > MAX_PAGES) {
            throw invalid("pages must be an array within the limit");
        }
        List<FoundPage> pages = new ArrayList<>();
        int total = 0;
        for (JsonNode page : node) {
            JsonNode base = page.get("base");
            JsonNode links = page.get("links");
            if (!page.isObject() || links == null || !links.isArray() || (base != null && !base.isNull() && !base.isString())) {
                throw invalid("each page needs links and an optional base");
            }
            if (base != null && base.isString() && base.asString().length() > MAX_LINK_CHARS) {
                throw invalid("base is too long");
            }
            total += links.size();
            if (total > MAX_LINKS) {
                throw invalid("too many links");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode link : links) {
                if (!link.isString()) {
                    throw invalid("each link must be a string");
                }
                values.add(link.asString());
            }
            pages.add(new FoundPage(base == null || base.isNull() ? null : base.asString(), values));
        }
        return pages;
    }


    /** 비서가 읽기 도구에 적어 넣은 주소다. 없으면 빈 목록이다. 옛 fos-ctx 는 이 칸을 보내지 않는다. */
    private static List<String> typed(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_TYPED) {
            throw invalid("typed must be an array within the limit");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode url : node) {
            if (!url.isString() || url.asString().length() > MAX_LINK_CHARS) {
                throw invalid("each typed url must be a string within the limit");
            }
            values.add(url.asString());
        }
        return values;
    }


    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
