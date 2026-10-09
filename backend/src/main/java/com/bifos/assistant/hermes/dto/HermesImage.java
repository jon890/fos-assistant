package com.bifos.assistant.hermes.dto;

/** 실행 입력에 싣는 사진 한 장이다. {@code label} 은 이미지 앞에 붙는 글이고 {@code dataUrl} 은 {@code data:image/jpeg;base64,...} 다. */
public record HermesImage(String label, String dataUrl) {

    /** 이름표와 {@code data:} 주소의 길이만 낸다. 사진 본문이 로그나 시험 실패 메시지에 실리지 않게 한다. */
    @Override
    public String toString() {
        return "HermesImage[label=" + label + ", dataUrlLength=" + (dataUrl == null ? 0 : dataUrl.length()) + "]";
    }
}
