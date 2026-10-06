package com.bifos.assistant.hermes;

/**
 * 대시보드가 바인딩 설치나 떼기를 409 로 거절했다. 그 profile 의 설정이나 다른 커넥터와 충돌한다는 뜻이다.
 *
 * <p>대시보드는 이 경우 파일을 하나도 바꾸지 않는다. 응답 본문에는 profile 의 설정 이름이 섞일 수 있어 메시지와 cause 를
 * 담지 않는다.
 */
public class ConnectorInstallConflict extends RuntimeException {}
