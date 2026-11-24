package com.bifos.assistant.runtime.domain;

import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 실행 기록에 내용을 남기지 않는 도구의 이름이다. 관리자에게도 보이지 않는다.
 *
 * <p>사건 스트림({@code HermesRunEventStream})은 사건 종류마다 이 이름으로 도구 내용을 버린다. 실패한 도구의 오류 요약은 종류와
 * 상관없이 이 도구들 전부에서 남기지 않는다. 오류 글에도 같은 본문이 실릴 수 있기 때문이다(스펙 run-trace REQ6). 두 쪽이 같은
 * 목록을 쓰도록 이름은 여기 한 곳에 둔다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WithheldToolContent {

    /** Memory 본문을 읽는 Control Plane MCP 도구 이름의 끝이다. Hermes 는 서버 이름을 앞에 붙여 보낸다. */
    public static final String MEMORY_READ_TOOL_SUFFIX = "memory_read";

    /** 결과나 인자에 원고 본문이 실릴 수 있는 도구다. 정확한 이름과 {@code __} 뒤 이름만 가린다. */
    public static final List<String> CONTENT_DRAFT_TOOLS = List.of(
        "content_draft_write", "content_draft_read", "content_draft_preview", "content_draft_save");

    /** 검색어와 제목도 시작과 끝의 실행 사건에서 가린다. */
    public static final String MEMORY_SEARCH_TOOL_SUFFIX = "memory_search";

    /** 할 일을 다루는 Control Plane MCP 도구(제안, 목록, 끝내기) 이름에 든 글이다. 인자와 결과에 할 일 제목이 실린다. */
    public static final String FOLLOW_UP_TOOL_MARK = "follow_up_";

    /** 기억을 남기는 Control Plane MCP 도구 이름의 끝이다. 인자에 사람에 관한 사실이 실린다(ADR-20261007 / memory-remember). */
    public static final String MEMORY_REMEMBER_TOOL_SUFFIX = "memory_remember";

    /** 사진과 OCR 본문을 다루는 도구다. 정확한 이름과 {@code __} 뒤 이름만 가린다. */
    public static final List<String> MEDIA_OBSERVATION_TOOLS = List.of("list_media_observations", "record_media_observation");


    /** 사진·OCR 관찰 도구인가. */
    public static boolean mediaObservation(String toolName) {
        return named(toolName, MEDIA_OBSERVATION_TOOLS);
    }


    /** 원고 도구인가. */
    public static boolean contentDraft(String toolName) {
        return named(toolName, CONTENT_DRAFT_TOOLS);
    }


    /** 이 도구의 오류 글을 실행 기록에 남기지 않는가. 사건 스트림이 어느 사건에서든 내용을 버리는 도구 전부다. */
    public static boolean withholdsErrorText(String toolName) {
        if (toolName == null) {
            return false;
        }
        return mediaObservation(toolName)
            || contentDraft(toolName)
            || toolName.endsWith(MEMORY_READ_TOOL_SUFFIX)
            || toolName.endsWith(MEMORY_SEARCH_TOOL_SUFFIX)
            || toolName.endsWith(MEMORY_REMEMBER_TOOL_SUFFIX)
            || toolName.contains(FOLLOW_UP_TOOL_MARK);
    }


    private static boolean named(String toolName, List<String> names) {
        if (toolName == null) {
            return false;
        }
        for (String name : names) {
            if (toolName.equals(name) || toolName.endsWith("__" + name)) {
                return true;
            }
        }
        return false;
    }
}
