package com.bifos.assistant.chat.artifact.domain;

/**
 * 링크의 사진을 받지 못한 까닭이다(ADR-20261120 / link-image-source). 받기 경로가 까닭마다 다른 오류 코드로 바꾼다.
 */
public class LinkImageRejectedException extends RuntimeException {

    /** 받지 못한 까닭이다. */
    public enum Reason {
        /** https 가 아니거나, 이름이 풀리지 않거나, 내부망이나 금지 목록의 주소이거나, 넘기기가 너무 많다 */
        BLOCKED,
        /** 사진 형식이 아니고 대표 사진도 없다. 대표 사진이 받는 형식이 아니다 */
        NO_IMAGE,
        /** 사진이 상한을 넘는다 */
        TOO_LARGE,
        /** 연결하지 못했거나, 200 이 아닌 답이거나, 제한 시간을 넘겼거나, 동시 받기가 찼다 */
        FAILED
    }


    private final Reason reason;


    public LinkImageRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }


    public LinkImageRejectedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }


    public Reason reason() {
        return reason;
    }
}
