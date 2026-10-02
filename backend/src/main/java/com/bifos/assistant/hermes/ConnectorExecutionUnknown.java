package com.bifos.assistant.hermes;

/**
 * 승인한 호출의 실행을 보냈으나 실행됐는지 알 수 없다.
 *
 * <p>응답이 시간 안에 오지 않았거나 연결이 끊겼거나 읽을 수 없는 답이 왔을 때다. 받은 쪽은 다시 실행하지 않는다.
 * 원격 응답에는 실행 결과가 섞일 수 있어 메시지와 cause 를 담지 않는다.
 */
public class ConnectorExecutionUnknown extends RuntimeException {}
