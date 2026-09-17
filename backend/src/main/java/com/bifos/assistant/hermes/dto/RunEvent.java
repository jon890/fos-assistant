package com.bifos.assistant.hermes.dto;

public record RunEvent(String type, String text, String toolName, String detail) {
}
