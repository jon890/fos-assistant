package com.bifos.assistant.hermes.application.port;

/**
 * worker 가 그 stamp 의 원고 저장 job 을 모른다고 답했다.
 *
 * <p>timeout 이나 전송 실패와 다르다. 상태 파일이 없거나 정리됐고, worker 는 새로 뜨지 않는다. 받은 쪽이 start claim 유무로 job 을 닫는다.
 */
public class ContentDraftJobNotFound extends RuntimeException {
}
