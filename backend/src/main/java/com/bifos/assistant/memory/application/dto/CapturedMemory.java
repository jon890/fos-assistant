package com.bifos.assistant.memory.application.dto;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCapture;
import java.util.List;

/**
 * 대화에 그릴 기록 하나와 그 항목의 지금 값이다.
 *
 * @param replacedTitles 합치기에서 대신한(제안이면 대신할) 옛 기억 가운데 요청자가 지금 읽을 수 있는 것의 제목이다. 합치기가 아니면 빈 목록이다
 */
public record CapturedMemory(MemoryCapture capture, Memory memory, List<String> replacedTitles) {

    public CapturedMemory {
        replacedTitles = List.copyOf(replacedTitles);
    }
}
