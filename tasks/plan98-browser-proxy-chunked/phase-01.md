# Phase 01. Docker proxy 요청 길이 고정

**Execution profile**: standard

## 목표

브라우저 생성 요청이 chunked 를 거절하는 Docker proxy 를 통과하게 한다.

**범위 외**: proxy 정책 변경, Hermes core 변경, 운영 접속, 화면과 API 변경.

## 컨텍스트

`DockerProxyBrowserRuntime` 의 운영 생성자는 `SimpleClientHttpRequestFactory` 를 쓴다.
JSON Map 을 전송하면 직렬화 전에 길이를 알 수 없어 chunked 요청이 된다.
기존 `MockRestServiceServer` 시험은 실제 HTTP 전송을 생략해 이 문제를 잡지 못한다.

**근거 문서**: `docs/backend/user-browser.md` 의 설정과 API.

## 의도 메모

- 기존 timeout 과 JSON converter 를 유지하며 요청 factory 를 버퍼링한다.
- 본문 없는 요청도 Content-Length 0 을 보내는지 실제 TCP 서버에서 확인한다.
- backend 의 Docker proxy 호출과 셸 실행 공간 경로를 검색해 같은 구현이 있으면 보고한다.
- 버퍼링은 작은 Docker 제어 요청에만 적용한다. Hermes 실행 스트림에는 적용하지 않는다.

## 작업 항목

### 1. Docker proxy 호출 수정

`backend/src/main/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntime.java` 의
factory 를 `BufferingClientHttpRequestFactory` 로 감싸 JSON 직렬화 뒤 길이를 확정한다.
본문 없는 시작, 정지, 삭제도 길이를 갖게 한다. 정책 본문과 오류 처리는 유지한다.
`docs/backend/user-browser.md` 에 전송 길이와 TCP 회귀 시험 근거를 적는다.

### 2. 실제 TCP 회귀 테스트

`backend/src/test/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntimeHttpTest.java`
에서 임시 loopback HTTP 서버를 띄우고 운영 생성자를 사용한다.
Transfer-Encoding 이 있거나 Content-Length 가 없으면 403 을 돌려주는 proxy 를 만든다.
생성, 시작, 정지, 삭제 요청 순서와 메서드, 경로, query 를 확인한다.
생성 본문의 실제 byte 수와 Content-Length 가 같고 JSON 정책이 유지되는지 확인한다.
본문 없는 요청의 길이는 0 이어야 한다. 조회와 목록의 전송도 확인한다.
고치기 전 403 으로 실패하고 고친 뒤 통과한 출력을 남긴다.

## 검증

`cd backend && ./gradlew test --tests '*DockerProxyBrowserRuntime*Test'`

종료 코드 0. 수정 전 새 HTTP 시험은 403 으로 실패해야 한다.

`cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck`

종료 코드 0. 무거운 검사는 공통 heavy-lock 으로 직렬화한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntime.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntimeHttpTest.java` | 신규 |
| `docs/backend/user-browser.md` | 수정 |
