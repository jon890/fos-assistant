package com.bifos.assistant.memory.application.model;

import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryCapture;

/** 대화에 그릴 기록 하나와 그 항목의 지금 값이다. */
public record CapturedMemory(MemoryCapture capture, Memory memory) {}
