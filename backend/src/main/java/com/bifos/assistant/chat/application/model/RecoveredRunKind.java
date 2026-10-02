package com.bifos.assistant.chat.application.model;

/** 기동 때 남은 실행의 종류다. 종류마다 적는 것과 아직 돌 때 하는 일이 다르다. */
public enum RecoveredRunKind {
    CHAT_TURN,
    DELEGATION,
    FLOW,
    AUXILIARY
}
