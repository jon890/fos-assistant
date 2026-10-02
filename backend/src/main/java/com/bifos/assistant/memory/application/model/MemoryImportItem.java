package com.bifos.assistant.memory.application.model;

import java.time.LocalDate;

/**
 * 묶음의 항목 하나다. {@code entryType} 과 {@code retrieval} 은 글로 받는다. 틀린 값을 요청 전체의 거절이 아니라 그
 * 항목의 REJECTED 로 답하기 위해서다(ADR-058).
 */
public record MemoryImportItem(
        String sourceRef,
        LocalDate sourceDate,
        String collection,
        String entryType,
        String documentKey,
        String title,
        String content,
        boolean sensitive,
        String retrieval) {}
