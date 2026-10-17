package com.bifos.assistant.context.application;

import com.bifos.assistant.context.application.dto.ContextBodyMode;
import com.bifos.assistant.context.application.dto.ContextFreshness;
import com.bifos.assistant.context.application.dto.ContextItem;
import com.bifos.assistant.context.application.dto.ContextSource;
import com.bifos.assistant.context.application.dto.ContextTrust;
import com.bifos.assistant.memory.domain.entity.Memory;
import java.util.List;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Memory 항목을 문맥 항목과 지시문의 한 줄로 옮긴다. 조립의 여러 구역이 같은 모양을 쓴다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class MemoryItems {

    private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n|\\n|\\r");


    static ContextItem item(
        Memory memory,
        ContextSource source,
        ContextBodyMode bodyMode,
        List<String> conflictsWith,
        String title,
        String body,
        ContextFreshness freshness) {
        return new ContextItem(
            source,
            ContextItem.memoryRef(memory.id()),
            memory.scope(),
            memory.ownerUserId(),
            memory.sensitivity(),
            ContextTrust.USER_APPROVED,
            memory.updatedAt(),
            freshness,
            bodyMode,
            conflictsWith,
            title,
            body);
    }


    /** 본문까지 싣는 한 줄이다. 제목과 본문의 줄바꿈은 공백 하나로 바꾸고 자르지 않는다. */
    static String inlineLine(Memory memory) {
        return "- [" + memory.id() + "] " + oneLine(memory.title()) + ": " + oneLine(memory.content());
    }


    /** 줄바꿈을 공백 하나로 바꾼다. 모델이 정한 제목이나 본문이 지시문에 머리 줄을 끼우지 못하게 한다. */
    static String oneLine(String text) {
        return LINE_BREAK.matcher(text).replaceAll(" ");
    }
}
