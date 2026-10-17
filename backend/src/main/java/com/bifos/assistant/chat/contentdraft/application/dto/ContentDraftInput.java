package com.bifos.assistant.chat.contentdraft.application.dto;

import tools.jackson.databind.JsonNode;

/** 서버가 채우는 식별자 없이 검증한 원고 본문이다. */
public record ContentDraftInput(String title, String category, JsonNode tags, JsonNode blocks) {
}
