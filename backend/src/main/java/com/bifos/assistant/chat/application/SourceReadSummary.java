package com.bifos.assistant.chat.application;

import java.util.List;

/** 저장된 실행 사건에서 확인한 원문 열람 근거다. */
public record SourceReadSummary(
        int completedCount, List<String> urls, int unresolvedCount, boolean observationComplete) {}
