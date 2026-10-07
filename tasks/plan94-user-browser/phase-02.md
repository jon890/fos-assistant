# Phase 02. proxy 클라이언트와 켜기, 끄기, 지우기 서비스

**Execution profile**: deep

## 목표

Control Plane 이 브라우저 proxy 로 컨테이너를 만들고 켜고 멈추고 지운다. 동시 수를 지키고, 실패하면 남은 컨테이너를 지우고 `FAILED` 로 둔다.

**범위 외**: 스케줄러(자동 중지, 상태 맞추기), API 와 화면, 원격 화면, 중계.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261007-user-browser.md`, `docs/backend/user-browser.md`

- 계약: `docs/backend/user-browser.md` 의 「상태 전이」 와 「설정」. proxy 가 받는 요청과 생성 본문의 규칙은 운영 저장소의 단계 0 PR 이 강제하며, 같은 계약을 아래 「proxy 요청」 에 옮겨 둔다
- `RestClient.Builder` 는 자동 구성되지 않는다. `RestClient.builder()` 로 만들고 timeout 을 준다(`backend/AGENTS.md`). 본보기: `hermes/HttpHermesConnectorClient.java`
- Jackson 3(`tools.jackson`) 을 쓴다
- 설정 record 본보기: `connector/application/ConnectorProperties.java`. `application.yml` 에는 env 로 받는 기본값을 두고, `src/test/resources/application-test.yml` 에 시험 값을 둔다
- 시각은 주입받은 `Clock` 을 쓴다. 로거는 `@Slf4j`

### proxy 요청

버전 접두사 없는 Docker Engine API 경로다.

| 하는 일 | 요청 |
| --- | --- |
| 목록 | `GET /containers/json?all=1&filters={"label":["fos-browser=1"]}` |
| 만들기 | `POST /containers/create`. 본문은 아래 |
| 켜기 | `POST /containers/{id}/start` |
| 멈추기 | `POST /containers/{id}/stop?t=10` |
| 지우기 | `DELETE /containers/{id}?force=1` |
| 조회 | `GET /containers/{id}/json`. 브라우저 망의 주소는 `NetworkSettings.Networks.<망>.IPAddress` |

만들기 본문: `Image`(설정), `Labels` 는 `fos-browser=1` 과 `fos-browser-user=<profile_key>` 둘, `HostConfig` 는
`Binds=["<profile-host-root>/<profile_key>:/example/profile:rw"]`, `NetworkMode`(설정), `Memory` 와 `MemorySwap`(같은 값), `NanoCpus`, `PidsLimit`, `ShmSize`,
`CapDrop=["ALL"]`, `SecurityOpt=["no-new-privileges"]`, `Init=true`, `RestartPolicy={"Name":"no"}`. `Entrypoint`, `Cmd`, `Env`, 이름은 보내지 않는다.

## 의도 메모

- 멈출 때 컨테이너를 지운다. 남는 것은 프로필 디렉터리뿐이다(ADR 의 「실행」)
- 켜기는 30초 동안 CDP `GET http://<주소>:<cdp-port>/json/version` 을 `Host: localhost` 로 부른다. 답하면 `RUNNING`
- 동시 수는 `STARTING` 과 `RUNNING` 을 센다. Control Plane 은 한 프로세스이므로 켜기 판정을 JVM 잠금 하나로 묶고, 그 안에서 세고 `STARTING` 을 저장한다. 잠금 안에서 컨테이너를 기다리지 않는다
- proxy 호출과 기다림은 트랜잭션 밖이다. 상태 저장만 짧은 트랜잭션으로 한다
- `assistant.browser.enabled` 가 false 면 모든 쓰기가 `BROWSER_DISABLED` 다
- 프로필 디렉터리 이름은 `profile_key` 하나뿐이고, 지울 때 링크를 따라가지 않는다

## 작업 항목

### 1. 설정

`browser.application.BrowserProperties`(`assistant.browser.*`): `enabled`, `proxyUrl`, `image`, `network`, `cdpPort`, `profileRoot`, `profileHostRoot`, `memoryMb`, `cpu`, `pidsLimit`, `shmMb`, `maxRunning`(기본 2), `idleTimeout`(기본 10분), `startTimeout`(기본 30초).
env 이름은 `ASSISTANT_BROWSER_ENABLED`, `ASSISTANT_BROWSER_PROXY_URL`, `ASSISTANT_BROWSER_IMAGE`, `ASSISTANT_BROWSER_NETWORK`, `ASSISTANT_BROWSER_CDP_PORT`, `ASSISTANT_BROWSER_PROFILE_ROOT`, `ASSISTANT_BROWSER_PROFILE_HOST_ROOT`, `ASSISTANT_BROWSER_MEMORY_MB`, `ASSISTANT_BROWSER_CPU`, `ASSISTANT_BROWSER_PIDS_LIMIT`, `ASSISTANT_BROWSER_SHM_MB`, `ASSISTANT_BROWSER_MAX_RUNNING`.
`enabled=false` 일 때 나머지가 비어도 기동한다. `docs/backend/user-browser.md` 의 「설정」 표를 이 목록으로 맞춘다.

### 2. port 와 구현

- `browser.application.BrowserRuntime`(port): `create(profileKey) → containerId`, `start(id)`, `stop(id)`, `remove(id)`, `cdpAddress(id) → Optional<URI>`, `list() → List<RuntimeContainer>`(id, profileKey, running)
- `browser.infra.DockerProxyBrowserRuntime`: 위 「proxy 요청」 을 `RestClient` 로 부른다. 오류 본문을 로그에 그대로 싣지 않는다
- `browser.application.BrowserProfileStore`(port)와 `browser.infra.FileBrowserProfileStore`: `ensure(profileKey)`(없으면 만들고 권한 700), `delete(profileKey)`(링크를 따라가지 않고 통째로 지운다). 키가 64자리 소문자 16진수가 아니면 거절
- `browser.application.CdpProbe`(port)와 `browser.infra.HttpCdpProbe`: `ready(URI) → boolean`, `Host: localhost`

### 3. 서비스

`browser.application.UserBrowserService`:

| 메서드 | 하는 일 |
| --- | --- |
| `get(userId)` | 없으면 빈 값 |
| `create(userId)` | 이미 있으면 `BROWSER_EXISTS`. 프로필 디렉터리를 만들고 `STOPPED` 줄을 저장 |
| `start(userId)` | `RUNNING` 이면 그대로 돌려준다. 잠금 안에서 수를 세고 넘치면 `BROWSER_CAPACITY`. `STARTING` 저장 → 만들기, 켜기, 기다리기 → `RUNNING`. 실패하면 컨테이너를 지우고 `FAILED`(코드 `start_failed` 나 `start_timeout`) 뒤 `BROWSER_START_FAILED` |
| `stop(userId)` | `STOPPING` 저장 → 멈추기, 지우기(없어도 성공) → `STOPPED` |
| `delete(userId)` | `stop` 뒤 프로필 디렉터리와 줄을 지운다 |
| `stopById(id)`, `deleteById(id)` | 관리자용. 같은 흐름 |
| `touch(userId)` | 단계 2 와 3 이 부른다. `RUNNING` 이면 `last_active_at` 을 갱신 |

### 4. 테스트

- `backend/src/test/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntimeTest.java`: `MockRestServiceServer` 로 만들기 본문의 칸(라벨 둘, Binds 하나, CapDrop, Entrypoint 없음)과 목록 필터를 확인
- `backend/src/test/java/com/bifos/assistant/browser/infra/FileBrowserProfileStoreTest.java`: 임시 디렉터리에서 만들기와 지우기, 링크를 따라가지 않음, 키 모양 거절
- `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserServiceTest.java`: 가짜 `BrowserRuntime` 과 `CdpProbe` 로 켜기 성공, 동시 수 초과 `BROWSER_CAPACITY`, 기다림 시간 초과에서 컨테이너가 지워지고 `FAILED`, 이미 `RUNNING` 인 켜기, 끄기, 지우기, `enabled=false` 의 `BROWSER_DISABLED`

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.browser.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew qualityCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserRuntime.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserProfileStore.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/CdpProbe.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/UserBrowserService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/model/**` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntime.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/FileBrowserProfileStore.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/HttpCdpProbe.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntimeTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/FileBrowserProfileStoreTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserServiceTest.java` | 신규 |
| `docs/backend/user-browser.md` | 수정 |
