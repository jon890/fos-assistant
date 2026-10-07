package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.shared.auth.CurrentUser;

/** 호출자다. 시스템 판단 profile 은 설치 설정에서만 정하고 요청으로 받지 않는다. */
public record DecisionRequest(CurrentUser user) {}
