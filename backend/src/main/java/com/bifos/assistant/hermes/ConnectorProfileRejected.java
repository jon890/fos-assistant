package com.bifos.assistant.hermes;

/**
 * 대시보드가 바인딩 설치나 떼기를 401 로 거절했다. 그 profile 에 커넥터를 받는 표식이 없다는 뜻이다.
 *
 * <p>사람이 만든 profile 은 운영자가 표식을 둔 뒤에 받는다. 응답 본문을 담지 않으므로 메시지와 cause 가 없다.
 */
public class ConnectorProfileRejected extends RuntimeException {}
