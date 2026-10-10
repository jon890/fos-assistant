package com.bifos.assistant.chat.application;

/** 원본 또는 표시 방향의 영역을 픽셀 축소 없이 읽은 결과다. */
public record InspectedImage(String contentType, byte[] bytes) {}
