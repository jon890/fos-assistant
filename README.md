# fos-assistant

가족이 함께 쓰는 개인 AI 비서다.
Hermes Agent 를 Agent Runtime 으로 그대로 두고, 이 저장소는 그 앞의 Control Plane 과 웹 화면을 담당한다.

## 역할 분담

| 층 | 무엇을 맡는가 |
| --- | --- |
| Hermes Agent | 에이전트 실행, 도구 호출, subagent, session |
| Control Plane (`backend/`) | 사용자, 에이전트 접근 권한, Memory 접근 권한, 모델 라우팅, 사용량 집계 |
| Web (`web/`) | 대화, 실행 상태, Memory 확인, 사용량 확인 |

Hermes core 는 수정하지 않는다.
profile, API server, plugin hook 이라는 공식 확장 지점만 쓴다.
자세한 조사 결과는 [`docs/hermes-integration.md`](docs/hermes-integration.md)에 있다.

## 기술 스택

기존 개인 저장소와 같은 스택을 쓴다.

| 대상 | 선택 |
| --- | --- |
| Backend | Spring Boot 4.0.6, Java 21, Gradle Kotlin DSL |
| DB | MySQL 8.4 (운영), H2 (테스트) |
| Migration | Flyway |
| Web | Next.js 16, React 19, TypeScript, Tailwind v4 |
| 로그인 | NextAuth v5 Google OAuth |

## 로컬에서 돌리기

홈서버 없이도 전체 흐름을 확인할 수 있다.
`test/e2e` 가 Hermes Runs API 대역을 같은 프로세스에 띄운다.

```bash
node test/e2e/run.ts
```

로그인 토큰 발급부터 에이전트 등록, 대화 한 번, 사용량 기록과 비용 환산까지 한 번에 돌린다.
시나리오는 `test/e2e/scenarios/` 에 하나씩 나뉘어 있다.

개별 실행은 아래와 같다.

```bash
cd backend && ./gradlew test
cd web && pnpm install && pnpm typecheck && pnpm build
```

## 환경 변수

Backend 는 `backend/src/main/resources/application.yml`, Web 은 `web/.env.example` 을 본다.

| 이름 | 쓰는 곳 | 설명 |
| --- | --- | --- |
| `ASSISTANT_JWT_SECRET` | 양쪽 | 웹이 발급하고 Control Plane 이 검증하는 토큰의 HMAC 비밀값 |
| `HERMES_BASE_URL` | Backend | Hermes API server 주소 |
| `HERMES_PROFILE_KEY_DIR` | Backend | profile 이름으로 된 key 파일이 들어 있는 디렉터리 |
| `HERMES_DASHBOARD_BASE_URL` | Backend | profile 을 만드는 Hermes 대시보드 주소 |
| `HERMES_DASHBOARD_TOKEN` | Backend | 그 대시보드가 기계에게 여는 경로에 보낼 토큰 |
| `HERMES_SHARED_LISTENER_BASE_URL` | Backend | profile 접두를 붙여 부르는 공유 listener 주소 |
| `ASSISTANT_PRICING_CATALOG` | Backend | models.dev 가격표를 복사해 둔 파일. 없으면 비용을 비워 둔다 |

AI credential 은 이 저장소와 데이터베이스 어디에도 두지 않는다.
각 사용자의 credential 은 그 사람의 Hermes profile `.env` 안에만 있다.
