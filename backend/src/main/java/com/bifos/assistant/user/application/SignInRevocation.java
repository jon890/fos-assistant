package com.bifos.assistant.user.application;

/** 관리자가 끈 주소인지 답한다. 허용 목록을 아는 {@code people} 이 구현한다. */
public interface SignInRevocation {

    /** 그 주소가 꺼져 있는가. */
    boolean revoked(String email);
}
