# Phase 02. 이미지 URL 다운로드와 SSRF 방어

**Execution profile**: deep

## 목표

허용한 이미지 호스트의 공개 IP 에만 연결해 5MB 이하 이미지를 저장한다.

**범위 외**: 이미지 생성 호출, `image_gen` 권한 설정, MCP 도구 등록, 실제 호스트 설정과 배포.

## 컨텍스트

**근거 문서**: [연동 계약의 SSRF 방어](../../docs/hermes/tools-and-skills.md#주소-방식과-ssrf-방어), [ADR-028](../../docs/adr/ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md), [쓰기 모듈 배치](../../docs/code-architecture.md#mcp-로-쓰는-자리).
근거 문서 경로는 `docs/hermes/tools-and-skills.md`, `docs/adr/ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md`, `docs/code-architecture.md` 다.

본문 저장과 쓰기 경로 판정이 구현되고 검사를 통과한 뒤 수행한다.
그 기반은 `chat/application/ArtifactWriteService`, `chat/infra/ArtifactStore` 다.
현재 코드의 `ArtifactProperties` 는 `assistant.artifact` 의 뿌리와 보관 기간만 받는다.
`backend/gradle/libs.versions.toml` 에 전용 HTTP 클라이언트 라이브러리는 없다.
JDK 21 과 기존 의존성 안에서 구현하고 라이브러리를 추가하지 않는다.

## 의도 메모

- 일반 `HttpClient` 로 원래 호스트를 다시 부르면 DNS 재바인딩 방어가 사라진다. 검증한 IP 로 연결하는 계약을 지킨다
- 확인한 Hermes 소스는 FAL 응답 URL 을 그대로 돌려주며 공개 응답 예시의 URL 은 비어 있다.
  허용 호스트를 추정하지 않고 기본 목록을 비워 둔다
- 테스트는 외부 서비스와 실제 DNS 에 의존하지 않는다. 운영 코드에 loopback 허용과 TLS 검증 우회를 넣지 않는다

## 작업 항목

### 1. `ArtifactSourceProperties` 와 설정을 추가한다

`backend/src/main/java/com/bifos/assistant/chat/application/ArtifactSourceProperties.java` 에
`assistant.artifact.source` 설정을 받는 record 를 추가한다.
`backend/src/main/resources/application.yml` 에 아래 설정을 연결하고 실제 환경값은 적지 않는다.

| 설정 | 기본값 |
| --- | --- |
| `allowed-hosts` | 빈 목록 |
| `connect-timeout` | 5초 |
| `read-timeout` | 10초 |
| `total-timeout` | 30초 |

허용 호스트는 소문자 ASCII DNS 이름의 정확한 일치로 비교한다.
wildcard, scheme, 경로, userinfo, IP 리터럴과 포트가 섞인 설정은 기동 시 거절한다.
제한 시간은 양수여야 한다.
목록이 비면 주소 방식 호출만 실패시키고 본문 저장과 서버 기동은 유지한다.
기존 설정 바인딩 등록 방식은 `AssistantApplication` 의 `@ConfigurationPropertiesScan` 을 따른다.

### 2. `ArtifactSourceFetcher` 에 URL 과 DNS 판정을 구현한다

`backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactSourceFetcher.java` 를 신규로 둔다.
`fetch(URI source, String expectedContentType)` 는 제한된 이미지의 `byte[]` 를 반환한다.
DNS 조회와 IP 연결 부분은 테스트에서 대역을 주입할 수 있게 하되 운영의 거절 규칙은 항상 적용한다.

1. scheme 은 `https` 만 받는다. userinfo, fragment, IP 리터럴과 HTTPS 표준 포트가 아닌 포트를 거절한다.
   호스트가 없거나 허용 목록의 정확한 이름과 다르면 DNS 조회 전에 거절한다.
   접미사가 비슷한 공격 호스트, 뒤에 점이 붙은 호스트와 인코딩으로 숨긴 호스트도 거절한다.
2. A 와 AAAA 결과를 모두 검사한다. 하나라도 공개 IP 가 아니면 연결하지 않는다.
   RFC1918, loopback, link-local, unspecified, multicast, 예약 주소, CGNAT, IPv6 ULA 를 거절한다.
   IPv4-mapped IPv6 등 IPv4 를 담은 주소도 내부 IPv4 여부를 판정한다.
   `InetAddress.isSiteLocalAddress()` 하나로 끝내지 않는다.
3. 검사한 주소 목록을 연결 계층에 넘긴다. 원래 호스트를 다시 DNS 로 풀어 연결하지 않는다.
   재시도도 검사한 주소만 사용하고 실패하면 호출 전체 제한 안에서 끝낸다.

### 3. 검증한 IP 로 HTTPS 에 연결하고 응답을 제한한다

기존 전송 계층이 IP 고정과 TLS 호스트 검사를 함께 제공하지 못하면 JDK `Socket` 과 `SSLSocket` 을 사용한다.
`InetSocketAddress` 에 검증한 `InetAddress` 를 넣어 연결하고,
`SSLSocketFactory.createSocket(socket, originalHost, standardHttpsPort, true)` 로 TLS 를 감싼다.
`SSLParameters` 의 endpoint identification 은 `HTTPS`, SNI 는 원래 호스트로 지정한다.
인증서를 신뢰하는 기본 설정을 바꾸지 않는다.
기존 연결을 TLS 로 감싸는 계약은
[JDK 21 SSLSocketFactory](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/SSLSocketFactory.html) 를 참고한다.
전송 구현을 나누어도 `chat/infra` 안에 두고 별도의 범용 HTTP 프레임워크를 만들지 않는다.

HTTP/1.1 GET 은 원래 호스트의 `Host`, `Accept-Encoding: identity`, `Connection: close` 로 보낸다.
원래 URL 의 경로와 query 는 유지하되 CR/LF 를 거절하고 안전한 URI 인코딩을 사용한다.
Control Plane 의 토큰, 쿠키와 다른 인증 정보를 보내지 않으며 환경 프록시로 연결하지 않는다.
응답 파서는 상태 행과 머리글을 합쳐 16KB 까지만 읽고, 중복된 길이와 모순된 framing 은 거절한다.
`Content-Length` 또는 chunked, 연결 종료로 끝나는 본문을 제한된 크기로 처리한다.
추가 라이브러리 없이 구현하는 경우 이 파서도 실패 테스트로 검사한다.

| 검사 | 처리 |
| --- | --- |
| 301, 302, 303, 307, 308 과 다른 비 200 응답 | 거절. `Location` 을 새로 부르지 않음 |
| MIME | 매개변수를 빼고 확장자에 맞는 이미지 MIME 과 정확히 비교 |
| 압축된 응답 | 거절. 압축 해제로 크기 제한이 달라지는 것을 막음 |
| `Content-Length` 초과 | 본문을 읽기 전에 거절 |
| 길이가 없거나 실제 길이가 다름 | 실제 본문을 5MB 에서 1바이트까지만 더 읽고 초과를 판정 |
| 연결, TLS, 읽기 오류와 제한 시간 초과 | 스트림과 socket 을 닫고 저장하지 않음 |
| 느린 본문과 느린 DNS | 읽기 제한과 별도로 호출 전체 30초 제한을 적용 |

DNS 와 전송 대기용 공유 실행기는 최대 4개의 daemon thread 와 용량 0인 대기열을 쓴다. 포화 상태에서는 새 호출을 즉시 저장 실패로 거절한다.
`InetAddress` 의 진행 중인 DNS 조회는 취소를 보장하지 않으므로, 전체 30초가 지나면 호출자는 실패를 받고 남은 조회는 그 공유 실행기에만 머물게 한다.
전체 제한 시간이 지나면 전송을 취소하고 socket 을 닫는다.
오류와 로그에는 URL query, 응답 본문, 인증 정보와 내부 경로를 쓰지 않는다.

### 4. `ArtifactWriteService` 에 주소 방식을 연결한다

대화 주인을 확인한 뒤 경로와 이미지 확장자를 판정하고 `ArtifactSourceFetcher` 를 부른다.
`png`, `jpg`, `jpeg`, `gif`, `webp` 만 URL 방식으로 저장한다.
`ArtifactStore.contentTypeOf` 의 MIME 에 매개변수가 있으면 비교 전에 제거한다.
받은 이미지도 본문 방식과 같은 `ArtifactStore.write` 로 저장한다.
다운로드가 끝나기 전에 대상 파일을 열거나 자르지 않는다.
URL 방어와 다운로드 실패는 MCP 가 `isError: true` 로 바꿀 수 있는 저장 실패로 전달한다.

### 5. URL 저장과 SSRF 테스트를 추가한다

`backend/src/test/java/com/bifos/assistant/chat/ArtifactSourceFetcherTest.java` 를 신규로 만든다.
`ArtifactWriteServiceTest` 에 주소 방식과 기존 파일 보존 검사를 더한다.
DNS 대역, 연결 대상 기록, 응답 스트림 대역으로 아래 결과를 검증한다.
TLS 전송 테스트는 원래 호스트 인증, SNI 와 고정 IP 전달을 확인하고 인증서 이름 불일치를 거절한다.

| 입력 또는 상황 | 관측 결과 |
| --- | --- |
| 허용 호스트, 공개 IP, 올바른 MIME 의 5MB 이하 이미지 | 저장 성공, 각 허용 확장자의 바이트 수 일치 |
| 빈 허용 목록, HTTP, IP 리터럴, userinfo, 비슷한 호스트 | DNS 또는 전송 호출 없음 |
| 사설, loopback, link-local, IPv6 ULA, IPv4 를 담은 비공개 IPv6 | 연결 호출 없음 |
| 공개와 비공개가 섞인 A/AAAA 응답 | 모두 거절 |
| DNS 의 두 번째 결과가 내부 IP 인 대역 | DNS 는 한 번만 조회, 연결은 처음 검사한 IP 만 받음 |
| redirect 가 내부 주소를 가리킴 | 두 번째 요청 없음 |
| MIME 누락, 확장자 불일치, HTML 과 SVG, gzip | 저장되지 않음 |
| 정확히 상한, 상한보다 1바이트 큰 본문, 길이 없는 chunked | 크기 판정과 제한된 읽기 횟수 일치 |
| 중복 길이, 잘못된 chunk, 머리글 초과, 잘린 본문 | 안전하게 거절하고 연결을 닫음 |
| 연결 실패, 읽기 지연, 조금씩 오는 본문, DNS 지연 | 각 제한 시간과 전체 제한 적용 |
| DNS 조회 네 개가 멈춘 뒤 추가 요청 | 대기열을 늘리지 않고 정해진 시간 안에 저장 실패 |
| 기존 파일이 있는 상태에서 다운로드 실패 | 기존 바이트 보존, 임시 파일과 연결 정리 |
| 남의 대화와 지운 대화의 URL 요청 | DNS 와 HTTP 호출 없음 |

## 검증

외부 네트워크 없이 아래 테스트가 통과해야 한다. 종료 코드는 모두 0이다.

```bash
# cwd: backend/
./gradlew test --tests '*ArtifactSourceFetcherTest' --tests '*ArtifactWriteServiceTest' --tests '*ArtifactStoreWriteTest'
./gradlew test
```

```bash
# cwd: 저장소 root
scripts/check-public-safe.sh
```

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactSourceProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactSourceFetcher.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactWriteService.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactSourceFetcherTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactWriteServiceTest.java` | 수정 |
