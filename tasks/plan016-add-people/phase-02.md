# Phase 02. Control Plane 이 Hermes profile 을 만든다

**Execution profile**: deep

## 목표

profile 을 만들고 `API_SERVER_KEY` 를 넣는 일을 Control Plane 이 HTTP 로 한다.
사람을 더할 때 홈서버에 들어가지 않으려면 이것이 먼저 되어야 한다.

**범위 외**:
화면과 사람을 더하는 흐름 전체의 조립은 phase-03 이 한다.
이 phase 는 부르는 쪽 한 벌과 key 를 쓰는 자리를 만드는 데까지다.

Hermes 쪽에 붙일 plugin 은 **이 저장소가 만들지 않는다.**
비공개 저장소 `fos-home-infra` 가 소유한다. 아래 「선행 조건」을 본다.

## 컨텍스트

Hermes 대시보드에 profile 관리 API 가 있다.
무엇이 어떻게 도는지는 `docs/hermes-integration.md` 의 「profile 을 HTTP 로 만드는 길」이 갖는다.
**그 절을 먼저 읽는다.** 아래는 그 절에서 이 phase 에 쓰이는 것만 추린 것이다.

| 호출 | 하는 일 |
| --- | --- |
| `POST /api/profiles` | profile 을 만든다 |
| `PUT /api/env` | 그 profile 의 `.env` 에 값 하나를 쓴다 |
| `DELETE /api/profiles/{이름}` | 지운다. 되돌릴 때만 쓴다 |

**근거 문서**:
`docs/hermes-integration.md` 의 「profile 을 HTTP 로 만드는 길」,
`docs/flow.md` 의 「사람을 더할 때」 절,
`docs/adr/ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md`.

### 선행 조건

Hermes 쪽 plugin 이 없으면 대시보드가 401 을 돌려준다.
그 plugin 이 `POST /api/profiles` 와 `PUT /api/env` 를 토큰으로 여는 자리를 만든다.
key 디렉터리를 쓰기로 붙이는 것도 그 저장소가 한다.

**둘 다 없어도 이 phase 를 끝낼 수 있다.** 테스트는 대역을 쓴다.
운영에서 실제로 도는 것은 phase-03 을 끝낸 뒤에 확인한다.

## 의도 메모

- `clone_from` 을 쓰는 안을 버렸다.
  그 값을 주면 본뜬 profile 의 `API_SERVER_KEY` 까지 복사되어 key 하나로 둘이 열린다.
  실측으로 확인했고 `docs/hermes-integration.md` 가 그것을 적었다.
- 실패해도 만든 것을 남기는 안을 버렸다.
  반만 만들어진 profile 이 남으면 다음에 같은 이름으로 다시 만들 수 없다.
- key 를 데이터베이스에 두는 안은 검토하지 않았다.
  비밀값을 데이터베이스에 넣지 않는 것이 이 저장소의 규칙이다.

## 작업 항목

### 1. `hermes/HermesDashboardClient.java`

대시보드를 부르는 한 벌이다. `hermes` 패키지에 둔다.

같은 패키지의 `HttpHermesRunsClient` 가 `RestClient` 를 쓰는 방식을 그대로 따른다.
실패를 오류 코드로 옮기는 것은 `HermesCallFailure` 가 이미 한다. 그것을 쓴다.

낼 메서드 셋이다.

| 메서드 | 부르는 것 |
| --- | --- |
| `createProfile(String name)` | `POST /api/profiles`. `clone_from` 을 넣지 않는다 |
| `putEnv(String profile, String key, String value)` | `PUT /api/env` |
| `deleteProfile(String name)` | `DELETE /api/profiles/{이름}` |

주소와 토큰은 설정으로 받는다. `HermesProperties` 에 둘을 더한다.

| 이름 | 무엇 |
| --- | --- |
| `dashboardBaseUrl` | 대시보드 주소 |
| `dashboardToken` | `Authorization: Bearer` 로 보낼 값 |

**둘 다 기본값을 두지 않는다.** 비어 있으면 기동할 때 알아차려야 한다.
`application.yml` 에는 환경 변수를 읽는 자리만 두고 값을 적지 않는다.

### 2. `hermes/HermesProfileKeyStore.java` 에 쓰는 경로를 더한다

지금 `resolve` 하나뿐이다. 둘을 더한다.

| 메서드 | 하는 일 |
| --- | --- |
| `write(String profileName, String key)` | 그 이름의 파일을 만들고 mode 600 으로 둔다 |
| `delete(String profileName)` | 되돌릴 때 쓴다. 없으면 조용히 지나간다 |

**읽는 규칙과 쓰는 규칙이 같은 파일에 있어야 한다.** 파일 이름 규칙이 갈리지 않게 한다.
`resolve` 가 쓰는 `PROFILE_NAME` 정규식 검사를 쓰는 쪽에서도 똑같이 한다.

이미 있는 파일을 덮지 않는다. 있으면 오류로 끝낸다.

### 3. `people/application/ProfileKeyFactory.java`

key 값을 만든다. `java.security.SecureRandom` 으로 256비트를 뽑아
URL 에 쓸 수 있는 글자로 옮긴다. 새 의존을 넣지 않는다.

### 4. `people/application/HermesProfileProvisioner.java`

profile 하나를 끝까지 만드는 자리다. **순서와 되돌리기를 이 클래스가 안다.**

```
1. createProfile(name)
2. key = ProfileKeyFactory.next()
3. putEnv(name, "API_SERVER_MODEL_NAME", name)
4. putEnv(name, "API_SERVER_KEY", key)
5. keyStore.write(name, key)
```

**`.env` 에 넣는 것은 그 둘뿐이다.**

`API_SERVER_ENABLED` 와 `API_SERVER_HOST` 와 `API_SERVER_PORT` 를 **넣으면 안 된다.**
공유 listener 를 쓰는 profile 이 그 셋을 적으면 gateway 가 뜰 때
`SecondaryPortBindingConfigError` 로 그 profile 을 건너뛴다.
비공개 저장소 `fos-home-infra` 의 `enable-profile-api.sh` 가 그것을 실측으로 적어 두었고,
그 스크립트도 공유 listener 를 쓸 때는 셋을 빼고 둘만 쓴다.

**이 셋을 넣지 않는 것을 테스트로 고정한다.** 실수로 들어가면 그 profile 이 조용히 빠진다.

되돌리기는 만든 순서의 역순이다.

| 어디서 실패하면 | 무엇을 되돌리나 |
| --- | --- |
| 1번 | 없다 |
| 2번부터 5번 | `deleteProfile(name)` |

되돌리다 또 실패하면 **원래 오류를 던지고 되돌리기 실패를 로그로 남긴다.**
되돌리기 실패로 원인이 가려지면 안 된다.

### 5. 오류 코드를 더한다

`shared/error/ErrorCode.java` 에 둘을 더한다. 기존 이름 짓는 방식을 따른다.

| 코드 | 언제 |
| --- | --- |
| `HERMES_PROFILE_EXISTS` | 그 이름의 profile 이 이미 있다 |
| `HERMES_PROVISION_FAILED` | 만들다 실패해 되돌렸다 |

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/people/HermesProfileProvisionerTest.java` 를 만든다.
`HermesDashboardClient` 를 대역으로 바꿔 넣는다.

| 무엇 | 기대 |
| --- | --- |
| 다 성공한다 | profile 과 key 파일이 남는다. `.env` 에 key 가 들어갔다 |
| `putEnv` 가 실패한다 | `deleteProfile` 이 불렸다. **key 파일이 남지 않았다** |
| `keyStore.write` 가 실패한다 | `deleteProfile` 이 불렸다 |
| 되돌리기도 실패한다 | 원래 오류가 올라온다. 되돌리기 실패는 로그로만 |
| 이미 있는 이름 | `HERMES_PROFILE_EXISTS` |
| 성공했을 때 넣은 `.env` 항목 | `API_SERVER_MODEL_NAME` 과 `API_SERVER_KEY` 둘뿐이다 |
| 같은 경우 | `API_SERVER_ENABLED` 와 `API_SERVER_HOST` 와 `API_SERVER_PORT` 를 넣지 않았다 |

`HermesProfileKeyStoreTest` 도 만든다.

| 무엇 | 기대 |
| --- | --- |
| 쓴 뒤 읽는다 | 같은 값이 나온다 |
| 쓴 파일의 권한 | 주인만 읽고 쓴다 |
| 이미 있는 이름에 쓴다 | 오류. 덮지 않는다 |
| profile 이름이 규칙에 안 맞는다 | 오류. 파일을 만들지 않는다 |

`test/e2e` 의 Hermes 대역에 대시보드 경로 셋을 더한다.
`test/e2e/` 의 기존 대역이 `/v1/runs` 를 흉내 내는 방식을 그대로 따른다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

아래가 아무것도 내지 않아야 한다. 주소와 토큰이 코드에 들어가면 안 된다.

```bash
# cwd: 저장소 root
grep -rn "dashboardToken\s*=\s*\"" backend/src/main
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/HermesDashboardClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesProfileKeyStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/ProfileKeyFactory.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/application/HermesProfileProvisioner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/HermesProfileProvisionerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesProfileKeyStoreTest.java` | 신규 |
| `test/e2e/` | 수정 |

## 끝낸 뒤

`tasks/plan016-add-people/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 3으로 올린다.

**배포하지 않는다.** 부르는 쪽만 생겼고 아직 아무도 그것을 부르지 않는다.
phase-03 과 함께 배포한다.
