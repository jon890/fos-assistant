# Phase 02. 일회성 claim API와 인증 경계

**Execution profile**: deep

## 목표

승인 내용을 저장하고 실행 권한을 한 번만 소비한다.

**범위 외**: 실제 거래, 새 의존성, Hermes core 변경과 다른 계획의 병렬 구현이다.

## 컨텍스트

**근거 문서**: `docs/features/connector-policy.md`, `backend/docs/data-schema.md`와 `backend/docs/adr/ADR-20261008-data-encryption.md`다.

독립 critic 통과와 코디네이터의 별도 구현 dispatch가 있어야 시작한다.
선행 단계는 저장·strict 검증·공통 vector만 구현하고 `docs/adr/ADR-20261010-financial-execution-guard.md`를 만든다. 그 ADR은 저장 결정의 근거이며 아래 ticket·claim 입력 계약의 구현까지 완료했다는 뜻이 아니다. 이 단계는 그 ADR에 구현한 인증과 일회성 소비 계약만 통합한다.
119~126은 변경하지 않는다. 최신 main의 승인 회귀 테스트를 보존하고 이미지 승인 변경과 공용 파일을 동시에 편집하지 않는다.
구현 순서는 저장과 claim, prepare와 권한 전달, 매수매도, 정정취소다.
선행 코드가 없으면 `PHASE_BLOCKED: 선행 구현 미통합`으로 보고한다.

## 의도 메모

- 금융 도구를 WRITE로 바꾸지 않는다. FINANCIAL은 always이고 grant는 만들거나 사용하지 않는다.
- DESTRUCTIVE는 기존 RISK_NOT_OPEN을 유지한다.
- 공개 운영 정보와 자격 증명, 실행 권한 원문을 파일과 DB, 로그에 남기지 않는다.
- 한 PR은 운영 코드 1000줄 이하이고 설계와 작동하는 구현을 함께 담는다. 초과하면 코디네이터에게 기능 분할을 보고한다.

## 작업 항목

### 1. 구현

ConnectorExecutionTicket은 기존 HermesProperties.dashboardToken()에서 HMAC-SHA256(key=dashboardToken UTF-8, data=fos-approval-signing-key-v1 ASCII) 키를 유도해 60초 권한을 발급한다. 서명 입력은 fos-approval-claim-v1 ASCII와 payload의 base64url ASCII를 순서대로 붙인 바이트다. ticket 원문과 비밀키는 DB에 저장하지 않는다. ConnectorExecutionClaims는 사용자, action, 실행 내용 행을 차례로 잠그고 아래 claim 계약을 검사해 consumed_at을 채운다. controller와 DTO는 분리한다. /internal/connector-executions/claim 한 경로만 JWT filter 예외와 SecurityConfig permitAll에 넣고 controller에서 권한 인증을 반드시 수행한다. 일반 사용자 JWT, profile 토큰과 다른 내부 경로에 예외를 넓히지 않는다. consumedAt이 있거나 action이 UNKNOWN이면 다시 발급하거나 소비하지 못한다. 현재 금융 정책은 계속 차단한다.

권한의 ticketId는 선행 저장 모델의 ticket_id UUID 식별자와 대조하고 ticket 원문은 저장하지 않는다. ConnectorExecutionSnapshot의 실제 읽기 함수로 content_key_id와 owner·행·칸 AAD를 검증해 세 본문을 복호화한 뒤 원문 해시를 대조한다. 암복호화 실패는 평문이나 빈 본문으로 우회하지 않는다. 저장한 connection_updated_at과 binding_updated_at을 현재 연결과 바인딩의 updated_at과 각각 대조하고 어느 하나라도 달라지면 발급과 claim을 거절한다.

#### ticket과 claim 입력 계약

이 계약은 이미 승인한 원본 설계에서 이 단계에 필요한 정의만 담는다. 현재 기능 문서에 없는 claim 절이나 선행 저장 ADR의 미구현 인증 내용을 완료한 것으로 전제하지 않는다.
ticket은 `base64url(payload).base64url(HMAC-SHA256)`이고 payload에는 `v,ticketId,actionId,userId,agentId,connectionId,bindingId,profile,connectorId,tool,argsSha256,scopeSha256,issuedAt,expiresAt`가 있다. v는 정수 1, ticketId는 저장한 UUID 식별자다. action과 사용자, 에이전트, 연결·바인딩, profile과 커넥터, 원래 도구, 실행 args 및 scope 해시를 저장 값과 대조한다. issuedAt/expiresAt으로 발급 후 60초를 제한하고 승인 만료를 넘지 않는다. 서명과 ticket 원문은 DB, 로그, 모델과 화면에 두지 않는다.

| 대상 | 정확한 계약 |
| --- | --- |
| 요청 | POST /internal/connector-executions/claim, `{ticket,tool,argsSha256,scope}` |
| scope | 선행 저장·strict 계약의 scope object, 실제 커넥터 env에서 읽은 문자열과 저장 scope 대조 |
| 성공 | `{v:1,allowed:true,ticketId,expiresAt}` |
| 서명 인증 실패 | 401, 일반 JWT와 profile 토큰은 ticket 서명을 대신하지 못함 |
| 소비됨 또는 실행 불가능한 상태 | 409 |
| 입력 오류 | 400, strict 키·타입·원문 상한 검사 |

사용자 활성, action EXECUTING, 서명 및 저장 ticket_id, 미만료, 현재 연결·바인딩 READY, 소유자·profile, 두 revision, 대상 도구·정책, 실행 args 해시 및 scope 해시·값을 모두 확인한다. 어느 하나라도 실패하면 consumed_at을 채우지 않고 허용 응답을 내지 않는다. snapshot이 없는 기존 금융 action, 보호 계약 없는 금융 도구와 모든 DESTRUCTIVE는 계속 거절한다. ticket은 중복 발급하지 않고 재승인으로 저장한 실행 args나 clientOrderId를 바꾸지 않는다.
consumed_at이 NULL인 행만 잠금 아래 한 번 채우고 트랜잭션 커밋 뒤 성공 응답을 보낸다. 병렬 claim은 하나만 성공하며 응답 유실·재시작 뒤에도 이미 소비한 행을 재사용하지 못한다. UNKNOWN action은 발급과 claim 모두 거절하며 명시적 새 요청 예외 경로는 이 단계에서 열지 않는다. 실패 응답에 내부 거절 이유와 서명 원문을 노출하지 않는다. support 제공과 금융 노출, 자식 권한 전달 및 실제 주문 POST는 별도 후속 단계이며 이 phase에서 구현하지 않는다.

### 2. 테스트

검사 대상 파일: `backend/src/test/java/com/bifos/assistant/connector/ConnectorExecutionClaimTest.java`.

ConnectorExecutionClaimTest는 서명 변조, 다른 소유자와 연결, 만료, 정책 철회, 중복과 병렬 claim을 검사한다. HTTP 인증 경계를 실제 MVC 요청으로 확인하고 서명 없는 요청이 permitAll 이후에도 401임을 증명한다. 소비 커밋 뒤 응답 유실을 만들고 새 서비스 인스턴스가 같은 DB로 재사용을 거절하는지 확인한다.
암호문 변조와 key 부재, 다른 owner·행·칸 AAD, 연결 revision 변경, 바인딩 revision만 변경한 경우도 실제 발급·claim 함수로 거절함을 확인한다.
저장된 keyId에 따른 평문·복호화 분기는 현재 enabled()와 무관하게 선행 계약을 지킨다. 실제 DataKeyService의 key 삭제·KEK 부재 검증은 캐시를 비우거나 새 인스턴스를 사용한다.
실제 HermesProperties.dashboardToken() 설정으로 고정 키 유도·서명 vector를 검사하고 다른 토큰과 도메인 문자열이면 서명이 달라지는지 확인한다.
테스트는 가짜 자격 증명과 로컬 HTTP 서버를 사용하며 실제 거래 주소에 요청하면 실패한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorExecutionClaimTest' --tests '*ToolPolicyDecisionTest'
```

```bash
cd backend && ./gradlew qualityCheck
```

각 명령의 종료 코드가 0이어야 한다. 새 테스트가 실제로 발견되고 실행된 건수를 확인한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorExecutionTicket.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorExecutionClaims.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorExecutionClaimController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorActionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorExecutionClaimTest.java` | 신규 |
| `docs/adr/ADR-20261010-financial-execution-guard.md` | 수정 |

