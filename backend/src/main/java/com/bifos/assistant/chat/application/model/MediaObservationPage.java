package com.bifos.assistant.chat.application.model;

import java.util.List;

/** 본문 예산 안에서 반환한 마지막 항목만 다음 커서로 쓴다. */
public record MediaObservationPage(List<MediaObservationView> items, String nextAfterAssetId) {}
