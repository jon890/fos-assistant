package com.bifos.assistant.hermes.dto;

/** 실행 입력에 싣는 사진 한 장이다. {@code label} 은 이미지 앞에 붙는 글이고 {@code dataUrl} 은 {@code data:image/jpeg;base64,...} 다. */
public record HermesImage(String label, String dataUrl) {}
