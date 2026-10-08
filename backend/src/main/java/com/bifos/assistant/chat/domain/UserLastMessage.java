package com.bifos.assistant.chat.domain;

import java.time.Instant;

/** 사용자 한 명이 보낸 가장 최근 메시지 시각이다. */
public record UserLastMessage(Long userId, Instant at) {}
