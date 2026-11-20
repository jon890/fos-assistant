# 서버 비밀값

관리자가 서버 비밀값을 관리자 화면에서 넣고 바꾸며, Control Plane 이 그 값을 KEK 로 암호화해 데이터베이스에 두는 기능이다.

covers: `backend/src/main/java/com/bifos/assistant/crypto/domain/ServerSecrets.java`, `backend/src/main/java/com/bifos/assistant/crypto/infra/DatabaseServerSecrets.java`, `backend/src/main/java/com/bifos/assistant/browser/application/GatewaySecretService.java`, `backend/src/main/java/com/bifos/assistant/admin/secret/`, `web/src/components/admin/admin-secret-list.tsx`, `web/src/app/admin/secrets/`

요구와 결정은 스펙 [`secret-store/spec.md`](secret-store/spec.md), 구조는 설계 [`secret-store/design.md`](secret-store/design.md), 장기 결정은 [ADR-20261120 / server-secret](../adr/ADR-20261120-server-secret.md) 이 갖는다.
지금 비밀값을 두는 곳은 [`code-architecture.md`](../code-architecture.md) 의 「비밀값을 두는 곳」 이 갖는다.

## 지금 들어온 것

- `server_secret` 저장소와 KEK 암호화. 칸과 뜻은 [`backend/docs/data-schema.md`](../../backend/docs/data-schema.md) 의 「server_secret」 이 갖는다
- 관리자 「서버 비밀값」 화면(`/admin/secrets`). 원문은 보이지 않고 입력됨 여부, 앞부분, 마지막 변경만 보인다
- 브라우저 중계 서명 비밀. 서버가 새로 만들고, 다시 만들면 브라우저를 쓰는 플러그인 바인딩을 모두 다시 설치한다. 옛 환경 변수 값은 기동 때 한 번 가져온다

- 웹 푸시 VAPID 키(PR1b). 서버가 만들고 지울 수 있고(`POST /api/v1/admin/secrets/vapid/regenerate`, `DELETE .../vapid`), 웹 푸시는 이 값만 읽는다. 만들거나 지우면 모든 사용자의 등록 기기를 비우고, 만들 때는 기기가 있던 사용자마다 「알림 받기가 해제됐어요」 알림을 남긴다. 푸시로는 보내지 않는다

## 남은 것

관리자 화면의 VAPID 줄(웹 프록시 경로 `web/src/app/api/admin/secrets/vapid` 와 화면)은 아직 없다. 후속으로 더한다.
스펙의 PR 나눔대로 profile API key(PR2), provider API key 와 Hermes 동기화(PR3)가 남았다.
