# Phase 01. 바깥 주소에서 링크 사진을 받는 객체와 응답 해석

**Execution profile**: standard

## 목표

허용 주소 목록 없이 공개 https 주소에서만 사진이나 페이지의 대표 사진을 받는 받기 객체를 만든다. 넘기기와 여러 줄의 `Set-Cookie` 를 읽도록 기존 응답 해석을 고친다.

**범위 외**: 대화 메시지 확인과 받기 경로 연결(phase 02), 링크 저장(phase 03), fos-ctx(phase 04).

## 컨텍스트

**요구사항**: REQ21

**근거 문서**: `docs/features/generated-image/spec.md`, `docs/features/generated-image/design.md` 의 「PR5: 사용자가 보낸 링크의 사진」 의 「바깥 주소에서 받기」, `docs/adr/ADR-20261120-link-image-source.md`

- 기존 받기는 `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ArtifactSourceFetcher.java` 다. `validate`(https, 443, 사용자 정보와 조각 없음, 호스트 형식), `isPublic`(공개 IP 판정), DNS 를 풀어 IP 에 고정 연결하는 흐름과 `ArtifactSourceTransport`, `ArtifactSourceDnsResolver`, `ArtifactSourceCancellation` 을 같이 쓴다.
- 스티커 받기(`artifact_write`)가 같은 전송을 쓴다. 그쪽은 200 이 아니면 거절하고 넘기기를 따라가지 않는 동작이 그대로여야 한다.
- 지금 `ArtifactSourceSocketTransport.parse` 는 상태가 200 이 아니면 머리 없이 `status 0` 을 돌려주고, 같은 머리가 두 번 오면 `IOException` 을 던지며, 머리 상한은 16KiB(`MAX_HEADERS`)다. 요청 머리는 `Host`, `Accept-Encoding: identity`, `Connection: close` 뿐이다(`requestBytes`).

## 의도 메모

- HTML 파서 의존성을 더하지 않는다(설계 DECISION20). meta 태그만 정규식이나 작은 손 해석기로 찾는다.
- 링크 받기는 스티커 받기와 실행기와 상한을 나눠 갖는다(설계 DECISION19). `ArtifactSourceFetcher.MAX_BYTES`(5MiB)를 쓰지 않는다.
- 이름이 풀리지 않는 것과 내부망으로 풀리는 것을 같은 까닭(`BLOCKED`)으로 낸다. 서버 쪽 DNS 의 내부 이름을 알아내지 못하게 한다.
- 로그에는 호스트와 까닭만 남긴다. 경로와 쿼리는 남기지 않는다.

## 작업 항목

### 1. `ArtifactSourceSocketTransport` 의 응답 해석과 요청 머리

- `parse` 가 상태 줄(`HTTP/1.[01] <3자리>`)의 상태 코드와 머리를 늘 읽어 `ArtifactSourceResponse(status, headers, body)` 로 돌려준다.
- 겹치면 안 되는 머리(`content-type`, `content-length`, `transfer-encoding`, `content-encoding`, `location`)만 겹칠 때 `IOException` 을 던지고 그 밖의 겹친 머리는 처음 값만 남긴다.
- 스티커 받기의 `parse(Socket, InputStream)` 는 지금 동작(200 이 아니면 상태 0, 모든 머리 겹침 거절, 16KiB)을 그대로 두고, 링크 받기용 `parseAnyStatus(Socket, InputStream, int maxHeaderBytes)` 를 더한다. 두 해석은 머리 읽기와 본문 틀 처리를 같이 쓴다.
- 스티커 받기의 `ArtifactSourceTransport` 는 그대로 두고, 링크 받기용 `LinkImageTransport`(요청 머리와 머리 상한을 담은 `Request`)를 새로 두어 `ArtifactSourceSocketTransport` 가 함께 구현한다. 스티커 받기의 가짜 전송(테스트의 lambda)을 고치지 않아도 된다. `requestBytes` 가 그 머리를 덧붙인다. 머리 값의 CR, LF 는 거절한다.
- `ArtifactSourceFetcher.validateResponse` 는 지금처럼 200 이 아니면 거절한다. 스티커 받기는 빈 요청 머리와 16KiB 로 부른다.

### 2. `backend/src/main/java/com/bifos/assistant/chat/artifact/config/LinkImageProperties.java` 신규

`assistant.artifact.link-image` 접두. `connectTimeout`(3초), `readTimeout`(5초), `totalTimeout`(10초), `maxRedirects`(5), `maxPageBytes`(2MiB), `maxImageBytes`(20MiB), `maxHeaderBytes`(64KiB), `blockedHosts`(소문자 DNS 이름 목록, 기본 빈 목록), `blockedCidrs`(CIDR 목록, 기본 빈 목록). `ArtifactSourceProperties` 의 검증 방식(compact constructor)을 따른다. 기본값은 `backend/src/main/resources/application.yml` 의 `assistant.artifact` 아래에 둔다. 금지 목록이 둘 다 비면 기동 때 경고 로그 한 줄을 남긴다. 실제 값은 적지 않는다.

### 3. `backend/src/main/java/com/bifos/assistant/chat/artifact/domain/LinkImageRejectedException.java` 신규

까닭 enum `Reason { BLOCKED, NO_IMAGE, TOO_LARGE, FAILED }` 을 갖는 예외. phase 02 가 오류 코드로 바꾼다.

### 4. `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/LinkImageFetcher.java` 신규

공개 메서드 `LinkImage fetch(URI link)`. 생성자는 `ArtifactSourceFetcher` 와 같은 꼴로 둘이다. `@Autowired` 운영 생성자 `(LinkImageProperties)` 와 테스트용 `(LinkImageProperties, ArtifactSourceDnsResolver, ArtifactSourceTransport)`. `LinkImage` 는 `record LinkImage(String contentType, byte[] bytes)` 로 같은 패키지나 `chat/artifact/infra` 에 둔다.

- 주소 검사를 두 벌로 만들지 않는다(설계 DECISION19). `ArtifactSourceFetcher.validate` 의 허용 목록 앞부분(https, 443, 사용자 정보, 조각, 호스트 형식, 제어 문자)을 package-private static 메서드로 빼서 두 객체가 같이 부르고, `ArtifactSourceFetcher` 는 그 뒤에 허용 목록만 더 본다. 링크 받기에서는 그 거절(`ArtifactSourceRejectedException`)을 `BLOCKED` 로 바꾼다
- 주소마다: 위 주소 검사 → `blockedHosts` 검사 → 이름 풀기, 모든 주소가 `isPublic` 이고 `blockedCidrs` 밖 → 그중 하나에 고정 연결. `isPublic` 은 `ArtifactSourceFetcher` 의 static 메서드를 그대로 부른다.
- 301, 302, 303, 307, 308 이면 `Location` 을 현재 주소 기준으로 풀어 다시 한다. `maxRedirects` 를 넘으면 `BLOCKED`. `Location` 이 없거나 주소로 풀리지 않으면 `FAILED`. 넘기기 횟수는 페이지 주소와 대표 사진 주소를 합쳐 링크 하나에 5번이다.
- 요청 머리: `Accept: image/png, image/jpeg, image/webp, image/gif, text/html;q=0.9`, 일반 브라우저 꼴 `User-Agent`(개인 정보 없는 고정 글), `Accept-Encoding: identity`. 쿠키와 인증 머리는 보내지 않는다.
- 응답이 `text/html` 이면 앞 `maxPageBytes` 만 읽어 대표 사진 meta 를 찾는다. 순서 `og:image`, `og:image:secure_url`, `twitter:image`. `property` 와 `name` 모두, 속성 순서와 따옴표 종류 무관, `content` 값의 HTML 엔티티(`&amp;`, `&quot;`, `&#39;`, `&#x2F;` 등 숫자 엔티티)를 푼다. 상대 주소는 최종 페이지 주소로, `//` 는 https 로 푼다. 사진 주소를 같은 단계로 받는다. 페이지에서 페이지로는 한 번만 간다. 대표 사진이 없으면 `NO_IMAGE`.
- 사진은 `image/png`, `image/jpeg`, `image/webp`, `image/gif` 이고 머리 바이트가 그 형식이어야 한다. 아니면 `NO_IMAGE`. `maxImageBytes` 를 넘으면 `TOO_LARGE`(Content-Length 로 먼저, 읽는 중에도). `content-encoding` 이 있으면 `FAILED`.
- 연결 실패, 200 이 아닌 최종 답, 제한 시간, 실행기 포화는 `FAILED`. 실행기는 이 객체 전용(동시 4개, `AbortPolicy`), `totalTimeout` 은 넘기기와 페이지와 사진을 합친 링크 하나의 시간이다.

### 5. 테스트

- `backend/src/test/java/com/bifos/assistant/chat/artifact/infra/ArtifactSourceResponseParseTest.java` 신규: 3xx 의 상태와 `Location`, 겹친 `set-cookie` 를 받는다. 겹친 `content-length` 와 `location` 은 거절한다. 머리 상한을 넘으면 거절한다.
- `backend/src/test/java/com/bifos/assistant/chat/ArtifactSourceFetcherTest.java` 수정: 스티커 받기가 302 와 404 를 지금처럼 거절하는지 더한다.
- `backend/src/test/java/com/bifos/assistant/chat/artifact/infra/LinkImageFetcherTest.java` 신규: 가짜 DNS 와 가짜 전송으로 아래를 본다.
  - 받는다: 사진 주소, 대표 사진이 있는 페이지(`property`, `name`, `content` 가 앞, 엔티티가 든 값), 상대 주소와 `//` 주소, 넘기기 5번, 허용 목록 설정이 비어도 공개 주소
  - `BLOCKED`: http, IP 글자, 사용자 정보, 443 밖 포트, 내부망과 예약 주소(10/8, 127/8, 169.254/16, 192.168/16, ::1, fc00::/7)로 풀리는 이름, 풀리지 않는 이름, 넘기기 끝의 내부망 주소, `blockedHosts` 와 `blockedCidrs`, 6번째 넘기기
  - `NO_IMAGE`: 대표 사진 없는 페이지, SVG, AVIF, 머리 바이트가 틀린 PNG
  - `TOO_LARGE`: Content-Length 초과와 읽는 중 초과
  - `FAILED`: 404, 제한 시간, `content-encoding: gzip`
  - 2MiB 를 넘는 페이지는 앞 2MiB 에서 meta 를 못 찾으면 `NO_IMAGE`

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.artifact.infra.LinkImageFetcherTest' --tests 'com.bifos.assistant.chat.artifact.infra.ArtifactSourceResponseParseTest' --tests 'com.bifos.assistant.chat.ArtifactSourceFetcherTest' --tests 'com.bifos.assistant.chat.artifact.infra.ArtifactSourceTlsTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ArtifactSourceSocketTransport.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ArtifactSourceFetcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/LinkImageFetcher.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/LinkImage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/LinkImageTransport.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/LinkPageParts.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/config/LinkImageProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/domain/LinkImageRejectedException.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/artifact/infra/ArtifactSourceResponseParseTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/artifact/infra/LinkImageFetcherTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactSourceFetcherTest.java` | 수정 |
