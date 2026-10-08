package com.bifos.assistant.memory.application.model;

/** 관리자가 에이전트에 줄 collection 하나와 그 민감 허용이다. */
public record AgentMemoryGrantInput(String collection, boolean allowSensitive) {}
