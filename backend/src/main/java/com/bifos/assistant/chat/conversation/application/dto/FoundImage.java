package com.bifos.assistant.chat.conversation.application.dto;

/** 읽은 페이지에 적힌 사진 주소와, 사진을 고를 때 사용하는 출처 및 크기다. */
public record FoundImage(String url, Kind kind, long size) {
    public enum Kind {
        PRODUCT,
        OG,
        BODY
    }
}
