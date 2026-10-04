# 직접 띄울 때 필요한 것

이 저장소를 받아 자기 환경에 띄우는 사람이 읽는다.
기술 스택과 주요 환경 변수를 이 문서가 갖는다.
단계별 설치 안내는 아직 없다.
Hermes 에 설치하는 묶음과 그때 받는 값은 [`hermes/README.md`](../hermes/README.md) 가 갖는다.

## 기술 스택

| 대상 | 선택 |
| --- | --- |
| Backend | Spring Boot 4.0.6, Java 21, Gradle Kotlin DSL |
| DB | MySQL 8.4 (운영), H2 (테스트) |
| Migration | Flyway |
| Web | Next.js 16, React 19, TypeScript, Tailwind v4 |
| 로그인 | NextAuth v5 Google OAuth |

## 개별 실행

전체 검사는 [`AGENTS.md`](../AGENTS.md) 의 「확인」 절이 갖는다.
한 쪽만 돌릴 때는 아래와 같다. 각 줄은 저장소 root 에서 따로 돈다.

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test)
(cd web && pnpm install && pnpm typecheck)
```

`pnpm build` 는 자리표시자 환경 변수가 있어야 통과한다. 그 값은 [`web/AGENTS.md`](../web/AGENTS.md) 의 「검사」 절이 갖는다.

## 환경 변수

아래 표는 주요 변수만 적는다.
전체 목록은 Backend 의 `backend/src/main/resources/application.yml` 과 Web 의 `web/.env.example` 이 갖는다.

| 이름 | 쓰는 곳 | 설명 |
| --- | --- | --- |
| `ASSISTANT_JWT_SECRET` | 양쪽 | 웹이 발급하고 Control Plane 이 검증하는 토큰의 HMAC 비밀값 |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Backend | 데이터베이스 접속 값 |
| `AUTH_GOOGLE_ID`, `AUTH_GOOGLE_SECRET`, `AUTH_SECRET` | Web | Google OAuth 클라이언트와 NextAuth 세션 암호화 key |
| `CONTROL_PLANE_BASE_URL` | Web | Control Plane 주소 |
| `APP_NAME` | Web | 사이드바 머리, 로그인 화면, 브라우저 제목에 보일 앱 이름. 웹 서버가 실행할 때 읽으므로 이미지를 다시 빌드하지 않고 바꾼다. 비우면 `fos-assistant` |
| `ASSISTANT_MEMORY_ENCRYPTION_ACTIVE_KEY_ID` | Backend | 민감 Memory 본문을 새로 암호화할 때 쓰는 key 의 id. 비우면 민감 항목을 저장하거나 수정하지 못한다 |
| `ASSISTANT_MEMORY_ENCRYPTION_KEYS` | Backend | `<id>:<base64 32바이트>` 를 쉼표로 이은 목록. 잃으면 민감 본문을 되찾지 못한다 |
| `HERMES_PROFILE_KEY_DIR` | Backend | profile 이름으로 된 key 파일이 들어 있는 디렉터리 |
| `HERMES_DASHBOARD_BASE_URL` | Backend | profile 을 만드는 Hermes 대시보드 주소 |
| `HERMES_DASHBOARD_TOKEN` | Backend | 그 대시보드가 기계에게 여는 경로에 보낼 토큰 |
| `HERMES_SHARED_LISTENER_BASE_URL` | Backend | profile 접두를 붙여 부르는 공유 listener 주소 |
| `ASSISTANT_PRICING_CATALOG` | Backend | models.dev 가격표를 복사해 둔 파일. 없으면 비용을 비워 둔다 |
| `ASSISTANT_ATTACHMENT_ROOT` | Backend | 대화에 올린 사진을 두는 디렉터리. Control Plane 이 쓴다. 비면 기동이 실패한다 |
| `ASSISTANT_ATTACHMENT_AGENT_ROOT` | Backend | 같은 디렉터리를 Hermes 컨테이너에서 보는 경로. 실행 입력에 적는다. 비면 기동이 실패한다 |
| `ASSISTANT_ARTIFACT_ROOT` | Backend | 에이전트가 만든 결과물 파일을 두는 디렉터리. Control Plane 이 읽고 오래된 것을 지운다. 비면 기동이 실패한다 |
| `ASSISTANT_ARTIFACT_AGENT_ROOT` | Backend | 같은 디렉터리를 Hermes 컨테이너에서 보는 경로. 실행 입력에 적는다. 비면 기동이 실패한다 |
| `ASSISTANT_SKILL_ROOT` | Backend | 에이전트에 올린 스킬을 profile 별 버전 디렉터리로 두는 디렉터리. Control Plane 이 쓴다. 비면 기동이 실패한다 |
| `ASSISTANT_SKILL_AGENT_ROOT` | Backend | 같은 디렉터리를 Hermes 컨테이너에서 읽기 전용으로 보는 경로. `skills.external_dirs` 에 적는다. 비면 기동이 실패한다 |
| `ASSISTANT_SKILL_MAX_PER_AGENT` | Backend | 에이전트 하나에 올릴 수 있는 스킬 수. 기본 30. 새 스킬을 만들 때만 본다 |

AI credential 은 이 저장소와 데이터베이스 어디에도 두지 않고 Hermes 쪽에 둔다.
provider API key 는 profile `.env` 에 둔다. OAuth 로그인은 profile 에 자기 `auth.json` 이 없으면 Hermes 루트의 로그인을 여러 profile 이 함께 쓴다.
그래서 profile 을 나눠도 대화와 Memory 는 갈리지만 AI 계정과 과금은 함께 쓸 수 있다.
무엇이 갈리고 무엇을 함께 쓰는지는 [`hermes/README.md`](hermes/README.md) 의 OAuth credential 절이 갖는다.
