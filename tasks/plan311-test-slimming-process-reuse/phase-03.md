# Phase 03. 합성 image 를 CI 캐시에 두고 실제로 기다리는 timeout 검사를 줄인다

**Execution profile**: standard

## 목표

`ContentDraftJobLoopbackTest` 가 첫 검사에서 빌드하는 합성 Docker image(66-76초)를 CI 캐시로 받고, 5초 HTTP 제한을 실제로 기다리는 검사 4건에 짧은 제한을 준다.
추정으로 unit 이 약 1.5분, MySQL 이 약 10초 줄어든다.

**범위 외**: 다른 job.

## 컨텍스트

**근거 문서**: `docs/features/content-draft.md`, `.github/workflows/ci.yml` 의 `backend-tests`

- image 태그는 Dockerfile 과 lock 파일의 hash 다. 이 hash 를 그대로 캐시 키로 쓴다.
- timeout 검사는 `ConnectorExecutionClaim*` 두 건과 `HttpContentDraftSnapshotClientTest` 두 건이다.

## 의도 메모

- 캐시 키가 틀리면 옛 image 로 돈다. 키에 Dockerfile 과 lock 파일 hash 를 모두 넣는다.
- 제한이 너무 짧으면 CI 에서 흔들린다. 주입하는 제한은 검사가 기다리는 응답 지연의 두 배를 넘지 않게 둔다.

## 작업 항목

### 1. image 캐시

`ci.yml` 의 `backend-tests` 에 image 를 복원하는 step 을 더하고, 없을 때만 빌드한 뒤 저장한다.

### 2. timeout 주입

운영 코드가 읽는 5초 제한을 검사에서만 바꿀 수 있게 `@OverrideProperties` 로 짧은 값을 준다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ContentDraftJobLoopbackTest' --tests '*HttpContentDraftSnapshotClientTest'
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `.github/workflows/ci.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpContentDraftSnapshotClientTest.java` | 수정 |
