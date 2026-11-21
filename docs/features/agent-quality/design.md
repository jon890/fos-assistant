# 에이전트 품질 기술 설계

스펙 [`spec.md`](spec.md) 의 PR1(REQ1 부터 REQ4 까지)과 PR2a(REQ5, REQ6, REQ9 의 「지금 돌리기」, REQ15 부터 REQ18 까지)를 설계한다. PR2a 는 아래 「PR2a: 평가 사용자로 묶음 문항 돌리기」 절이 갖는다. PR2b 와 PR3 은 이 문서가 정하는 경계(저장 표, 평가 전용 사용자, 실행기)만 적고, 그 PR 을 시작할 때 절을 더한다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | 새 최상위 패키지 `quality` 의 실행기가 평가 전용 사용자로 기능마다 새 대화를 만들어 `ChatService.send` 로 문항을 보내고, 끝난 turn 의 실행 트리, 실행 사건, 결과물, 그 사용자의 데이터로 규칙 판정한다. 배포 파이프라인은 관리자 API `POST /api/v1/admin/quality/checks` 로 시작하고 결과를 조회한다 |
| REQ2 | `app_user.evaluation` 칸이 평가 전용 사용자를 표시한다. 실행기는 이 사용자의 `CurrentUser` 로만 돌고, 사용자 목록·에이전트 고르기·멘션·반응·사용량 집계의 조회가 이 사용자를 뺀다 |
| REQ3 | 확인이 실패로 끝나면 `NotificationService` 로 그룹의 `ADMIN` 마다 새 알림 종류 `QUALITY_CHECK_FAILED` 를 남긴다. 파이프라인은 같은 조회 API 의 `status` 로 자기 알림을 보낸다 |
| REQ4 | 웹 `/admin/quality` 첫 칸이 확인 목록 API 를 그린다. 실패 줄은 남겨 둔 평가 대화의 관리자 실행 상세로 잇고, 「다시 확인」 은 같은 기능 묶음으로 새 확인을 시작한다 |
| REQ5 | PR2a. 아래 「묶음 파일」 과 「판정」 |
| REQ6 | PR2a. 아래 「묶음 파일」 과 「판정」 |
| REQ7 | PR2b 가 설계한다. PR2a 의 평가 실행기와 `quality_run` 을 다시 쓴다 |
| REQ8 | PR2b 가 설계한다. PR2a 의 평가 실행기와 `quality_run` 을 다시 쓴다 |
| REQ9 | 「지금 돌리기」 는 PR2a 의 「평가 실행 API」 와 「화면」. 주 1회 실행은 PR2b 가 설계한다 |
| REQ10 | PR2b 가 설계한다. PR2a 의 평가 실행기와 `quality_run` 을 다시 쓴다 |
| REQ11 | PR3 가 설계한다. 반응은 `decision_feedback_event`, 문항 후보는 `quality_run` 의 JSON 칸으로 받는다(스펙 DECISION18, 이 문서 DECISION1) |
| REQ12 | PR3 가 설계한다. 반응은 `decision_feedback_event`, 문항 후보는 `quality_run` 의 JSON 칸으로 받는다(스펙 DECISION18, 이 문서 DECISION1) |
| REQ13 | PR3 가 설계한다. 반응은 `decision_feedback_event`, 문항 후보는 `quality_run` 의 JSON 칸으로 받는다(스펙 DECISION18, 이 문서 DECISION1) |
| REQ14 | PR3 가 설계한다. 반응은 `decision_feedback_event`, 문항 후보는 `quality_run` 의 JSON 칸으로 받는다(스펙 DECISION18, 이 문서 DECISION1) |
| REQ15 | PR2a. `quality` 의 평가 실행기가 평가 전용 사용자의 `CurrentUser` 로 `ChatService` 를 부른다(「평가 실행기」). 관리자 API 와 품질 화면 둘째 칸이 시작하고 결과를 보인다 |
| REQ16 | PR2a. 문항의 차례마다 `card` 를 적고, 실행기가 화면과 같은 승인 서비스를 평가 사용자로 부른다(「여러 차례와 승인 카드」) |
| REQ17 | PR2a. 문항마다 만든 것을 기록하고 실행 끝에 지운다(「비우기」) |
| REQ18 | PR2a. 기능 묶음 파일과 관리자 API 를 부르는 실행 스크립트, 루트 `AGENTS.md` 와 서비스 개념 문서의 규칙 변경(「기능 PR 의 배포 뒤 확인」) |

## 책임과 경계

| 모듈 | 맡는 것 |
| --- | --- |
| `backend` 의 `quality` | 평가 전용 사용자 준비, 확인 한 번의 시작과 상태 전이, 기능별 문항 실행과 규칙 판정, 끝난 확인의 정리, 실패 알림, `quality_run` 저장 |
| `backend` 의 `admin.quality` | 관리자 API. `requireAdmin` 뒤 `quality.application` 만 부른다(rule-5 의 `admin` 규칙) |
| `backend` 의 `people`, `user`, `agent` | 평가 전용 사용자의 허용 목록 행, Hermes profile, `app_user`, 에이전트를 기존 흐름으로 만든다. 목록 조회가 평가 전용 사용자를 뺀다 |
| `web` | `/admin/quality` 화면의 첫 칸, 관리자 메뉴 자리(관리자 영역 재편 스펙의 「살펴보기」 묶음 두 번째) |
| `fos-home-infra` | 배포 끝에 확인을 시작하고 결과를 받아 파이프라인 알림을 보낸다. 범위 밖이며 아래 「운영 저장소에 넘길 요청」 으로 넘긴다 |

**`quality` 는 최상위 패키지 층 순서에서 `workspace` 다음, `admin` 앞에 둔다.** 실행기가 `chat`, `usage`, `memory`, `task`, `workspace`, `people`, `notification`, `agent`, `orchestration` 을 부르고 아무 패키지도 `quality` 를 부르지 않는다. `TopLevelPackageOrder` 의 목록과 `backend/docs/code-architecture.md` 의 최상위 패키지 표에 더한다.
하위 기능은 두지 않는다. `application` 직하가 25개를 넘을 만큼 커지면 그때 나눈다.

**실행기는 Control Plane 안에 둔다(스펙 DECISION17).** 결과를 품질 화면과 앱 안 알림에 남기려면 Control Plane 이 결과를 가져야 하고, 실행기가 서비스를 바로 부르면 평가 전용 사용자의 토큰을 만들 필요가 없다.
`test/model-eval/` 의 TypeScript 실행기는 PR2 가 답변 품질 묶음을 옮길 때까지 그대로 둔다.

**문항은 사람이 보낸 질문과 같은 길로 보낸다.** `ChatService.send` 가 대화 잠금, 사용자 실행 한도, 공통 답변 지침, 실행 기록을 그대로 탄다. 확인을 위한 판정이나 허락을 따로 두지 않는다. 쓰기 도구의 승인 카드가 뜨면 그 기능은 실패로 판정한다(승인을 기다리는 확인 문항은 두지 않는다).

## 인터페이스와 데이터

### 평가 전용 사용자

| 무엇 | 정한 것 |
| --- | --- |
| 표시 | `app_user.evaluation` BOOLEAN NOT NULL DEFAULT FALSE. 사용자당 참/거짓 하나라 본체 컬럼에 둔다(data-schema 「테이블을 추가하는 기준」). 그룹마다 많아야 하나다 |
| 만들기 | 관리자가 품질 화면에서 「평가 사용자 준비」 를 한 번 누른다(`POST /api/v1/admin/quality/evaluation-user`). 예약 주소(`evaluation@quality.invalid` 꼴, `.invalid` 는 메일이 가지 않는 예약 도메인)로 차례대로 만든다. (1) `PersonRegistrar.register` 가 허용 목록 행과 Hermes profile, key, MCP 토큰을 만든다. (2) `UserProvisioningService.resolve` 가 `app_user`(역할 `MEMBER`)를 만들고 첫 로그인 리스너(`FirstAgentCreator`)가 첫 에이전트를 만든다. (3) 같은 트랜잭션에서 전용 `UPDATE` 로 `evaluation` 을 켠다(data-schema 「테이블을 추가하는 기준」 의 전용 `UPDATE` 규칙). (4) `AgentLifecycleService.create` 로 위임 대상 에이전트를 만든다. (5) 같은 트랜잭션에서 기본 비서의 기본 도구 대기 목록(`agent.pending_default_toolsets`)에 첫 로그인 기본 도구와 `image_gen`, `vision` 을 더한다. 첫 로그인의 백그라운드 작업이 커밋 뒤 기본 도구를 쓰고, 이어서 `AgentPendingToolsets.attempt` 가 행 잠금 안에서 그 목록을 실행 공간 안으로 켠다. 준비가 Hermes 도구 목록을 따로 읽고 통째로 쓰면 백그라운드 쓰기와 겹쳐 나중에 쓴 쪽이 다른 쪽 이름을 지우므로 직접 쓰지 않는다. 이미 있으면 빠진 것만 채우고, 도구는 같은 목록에 더한 뒤 `attempt` 를 바로 부른다. 실행 공간과 첨부 정책에 평가 profile 을 등록하는 일은 운영 저장소가 먼저 한다(아래 「운영 저장소에 넘길 요청」). 등록 전에 준비하면 그 도구가 켜지지 않으므로, 상태는 셸·파일 도구까지 모두 켜졌는지 보고 빠졌으면 「정책 등록 필요」(`POLICY_REGISTRATION_REQUIRED`), Hermes 가 목록을 돌려주지 않으면 `TOOLS_UNREADABLE` 로 돌려준다. 처음 준비한 직후에는 백그라운드 작업이 도는 중이라 잠시 「정책 등록 필요」 가 남을 수 있다 |
| 에이전트 | 기본 에이전트 하나와 위임 대상 에이전트 하나. 기본 에이전트의 기본 도구는 첫 에이전트를 만들 때처럼 실행 공간 여부를 보고 켜지고, `image_gen` 과 `vision` 은 기본 도구 대기 목록을 거쳐 `AgentDefaultToolsets.enablePending` 으로 실행 공간 안에서만 켠다. 셸·파일 도구를 실행 공간 밖으로 켜는 쓰기(`AgentToolService.writeAsAdmin`)는 쓰지 않는다. Hermes 내장 위임(`delegation`, `delegate_task`)은 켜지 않는다. 켜면 모델이 자식 실행 줄이 남지 않는 내부 하위 작업을 써 위임 확인이 흔들린다. 위임 확인은 늘 켜진 Control Plane MCP 의 `agent_delegate` 로 위임 대상 에이전트의 code 를 지정해 맡긴다. 그 code 를 문항에 넣어 다른 사용자의 공개 에이전트로 가지 않게 한다. 평가 사용자의 에이전트는 기본 도구 대기 알림(`DEFAULT_TOOLS_PENDING`)에서 뺀다 |
| 미리 넣는 기억 | 기억 읽기 확인용 개인 기억 한 줄. 그날 바뀌지 않는 지어낸 사실(예: 「평가 사용자가 좋아하는 색은 청록이다」)을 준비할 때 한 번 넣는다 |
| 로그인 | 하지 않는다. Google 동의 화면에 주소를 더하지 않는다. 실행기가 `app_user` 행으로 `CurrentUser` 를 만든다 |
| 숨기기 | 아래 「숨기는 곳」 표 |

**숨기는 곳.** 모두 `user` 보다 위 패키지라 `app_user.evaluation` 을 읽을 수 있다.

| 화면이나 경로 | 바꿀 패키지 | 바꾸는 것 |
| --- | --- | --- |
| 로그인 확인과 매 요청 인증 | `people`(`SignInPolicy.admit`, `recordCompletion`), `user`(`AllowedUserResolver.resolveAllowed`) | `evaluation` 행을 거절한다. 그래서 그 주소로 서명된 토큰이 생겨도 통과하지 못한다. 예약 작업 경로가 쓰는 `SignInPolicy.revoked` 는 거절하지 않는다 |
| 관리자 사용자 목록 | `people`(`PersonAccessService.list`) | 남기고 「평가용」 표시를 붙인다. 끄기와 지우기는 막지 않는다 |
| 에이전트 고르기, 공개 목록, 멘션 후보 | 없음 | 두 에이전트가 `PRIVATE` 이라 주인 말고는 읽지 못한다(`AgentService.readableBy`). 별도 필터를 두지 않는다. 관리자 에이전트 목록에는 남는다 |
| 사용량 집계 | `usage` | 사용자별 행에서 빼고 「평가」 한 줄로 합친다 |
| 기본 도구 대기 알림 | `agent`(`AgentPendingToolsets`) | 평가 사용자의 에이전트를 뺀다 |
| 반응 집계 | `feedback` | PR3 이 뺀다 |

### 저장: `quality_run`

확인이나 평가 한 번이 한 줄이다. 2026-11-01 「테이블을 늘리지 않는다」 의 예외로 사용자가 승인했다(이 문서 DECISION1). 답 본문, 질문 본문, 도구 인자는 담지 않는다.

| 칸 | 뜻 |
| --- | --- |
| `id`, `public_id` | 내부 번호와 화면·API 가 쓰는 공개 식별자 |
| `group_id` | 그룹 |
| `kind` | `DEPLOY_CHECK`. PR2 가 `EVAL`, PR3 가 `CANDIDATE` 를 더한다 |
| `trigger_type` | `PIPELINE`, `ADMIN`. PR2 가 `SCHEDULED` 를 더한다 |
| `status` | `RUNNING`, `PASSED`, `FAILED`. 끝난 줄은 다시 바뀌지 않는다 |
| `started_at`, `finished_at` | UTC |
| `item_count`, `model_call_count`, `elapsed_ms` | 문항 수, 모델 호출 수(실행 줄 수), 걸린 시간. 스펙 DECISION8 |
| `result_json` | 기능별 결과 배열. 항목마다 `feature`, `status`(`PASSED`, `FAILED`, `TIMED_OUT`), `reasonCode`, `elapsedMs`, `conversationPublicId`(실패만, 30일), `executionIds`(그 기능이 돈 루트 실행 번호 목록. 사진 조회의 두 turn, 위임의 자동 turn 을 모두 담는다. 실패만, 30일). 글은 고정 까닭 코드로만 담고 모델이 쓴 글을 넣지 않는다 |
| `requested_by_user_id` | 관리자가 시작했으면 그 사용자. 파이프라인도 관리자 토큰으로 부르므로 그 토큰의 사용자다 |

색인은 `(group_id, kind, started_at)` 이다. 줄은 지우지 않는다(스펙 DECISION16). 마이그레이션 번호는 구현 때 `orch queue reserve` 로 받는다.

### 관리자 API

| 요청 | 응답 |
| --- | --- |
| `POST /api/v1/admin/quality/checks` | 202 와 `{publicId, status}`. 도는 `DEPLOY_CHECK` 가 있으면 새로 만들지 않고 그 줄을 `200` 으로 돌려준다(같은 배포에서 두 번 불러도 하나). 평가 전용 사용자가 없으면 409 `EVALUATION_USER_MISSING` |
| `GET /api/v1/admin/quality/checks/{publicId}` | 한 줄과 기능별 결과. 파이프라인이 이 `status` 를 읽는다 |
| `GET /api/v1/admin/quality/checks?cursor=` | 최근 순 목록. 품질 화면 첫 칸 |
| `POST /api/v1/admin/quality/checks/{publicId}/retry` | 같은 기능 묶음으로 새 확인을 시작한다(`trigger_type` `ADMIN`). 도는 확인이 있으면 첫 줄처럼 그 줄을 돌려주고, 화면은 「이미 확인이 돌고 있다」 를 보인다 |
| `GET /api/v1/admin/quality/executions/{executionId}/tree` | 평가 사용자의 실행 트리만 연다. 다른 사용자의 실행은 404 다. 지금 실행 트리 조회(`ExecutionTreeService`)는 주인만 열어 관리자가 평가 사용자의 실패를 볼 길이 없다(ADR-063 의 관리자 영역 규칙대로 `admin.quality` 에 둔다) |
| `POST /api/v1/admin/quality/evaluation-user` | 평가 전용 사용자를 준비하고 상태를 돌려준다 |
| `GET /api/v1/admin/quality/evaluation-user` | 준비됐는지, 빠진 것 |

### 확인 한 번의 흐름

```mermaid
---
config:
  look: handDrawn
---
sequenceDiagram
    participant P as 배포 파이프라인
    participant A as admin.quality
    participant Q as quality 실행기
    participant C as ChatService
    participant H as Hermes
    P->>A: POST checks (관리자 토큰)
    A->>Q: start(PIPELINE)
    Q->>Q: quality_run RUNNING 저장
    loop 기능마다 차례로
        Q->>C: 새 대화, send(문항)
        C->>H: /v1/runs
        H-->>C: 답, 도구 사건
        C-->>Q: ChatTurn
        Q->>Q: 규칙 판정, 통과면 정리
    end
    Q->>Q: PASSED 나 FAILED 로 끝냄
    Q-->>Q: 실패면 관리자 알림
    P->>A: GET checks/{id} 되풀이
```

**기능은 차례로 돈다.** 사용자 실행 한도(`assistant.user-execution.max-running`)가 평가 전용 사용자에게도 걸리고, 위임 확인은 자식 실행 자리를 하나 더 쓴다. 차례로 돌면 한도에 닿지 않는다. 한 확인의 최대 시간은 기능 수 × 10분이고, 보통은 기능마다 1~3분이다.

**기능마다 10분 마감(스펙 DECISION19).** `ChatService.send` 는 끝날 때까지 막히고, 그 안의 `TurnCancellation.cancel` 은 표시만 바꿔 Hermes run 을 멈추지 않는다. 그래서 아래 순서로 한다.

1. 문항은 별도 작업(`BackgroundTasks`)에서 `send` 로 보낸다. 실행기 스레드는 그 기능의 끝남 조건을 1초 남짓 간격으로 본다.
2. 끝남 조건은 기능마다 다르다. 보통은 `send` 가 돌아온 것이다. 위임은 `send` 가 돌아오고, 자식 실행이 끝났고, `ChatService.deliveryStates` 의 그 대화 전달이 `DELIVERED` 이고, 대화에 도는 turn(`TurnCancellation.markOf`)이 없을 때다.
3. 10분이 지나면 도는 turn 의 실행 번호를 `TurnCancellation.markOf(conversationId)` 로 읽어 `ChatService.stop` 을 부르고, 도는 위임 자식은 `AgentDelegationService` 의 중지로 멈춘다. 그 기능을 `TIMED_OUT` 으로 적는다.
4. `send` 가 `HERMES_RUN_TIMEOUT` 으로 끝나면(Hermes 기다림 상한 `hermes.run-timeout`) 원격 run 이 남아 있을 수 있다. 그 실행을 `ChatService.stop` 으로 멈추고 `TIMED_OUT` 으로 적는다.
5. 대화에 도는 turn 이 없어진 뒤에 다음 기능으로 간다. 사용자 실행 자리를 겹쳐 쓰지 않게 한다.

**확인 하나는 서버 하나에서 돈다.** 시작은 `BackgroundTasks` 로 띄운다. 서버가 도중에 다시 뜨면 기동 때 `RUNNING` 인 줄을 `FAILED`, 까닭 `INTERRUPTED` 로 끝낸다. 다시 돌리지 않는다.

### 기능별 문항과 판정

문항 글은 실행기 코드의 상수로 둔다. 판정은 네트워크를 쓰지 않는 순수 함수로 나눠 단위 테스트한다.

| 기능 | 문항이 하는 일 | 통과 규칙 | 정리 |
| --- | --- | --- | --- |
| 검색과 출처 | 그날 바뀌는 일반 사실을 묻는다 | 트리에 `web_search` 의 `TOOL_COMPLETED` 가 실패 없이 있고, 답 끝 「출처」 절에 링크가 있다(`test/model-eval/answer/score.ts` 의 출처 절 규칙을 Java 로 옮긴다) | 대화 |
| 위임 | 위임 대상 에이전트에게 짧은 일을 맡기라고 한다 | 루트 트리에 `agent_delegate` 자식 실행이 성공으로 있고, 그 대화에 위임 결과를 전하는 자동 turn 이 성공으로 끝난다 | 대화 |
| 내 파일 넣기 | 짧은 글 파일을 만들어 파일 공간에 넣으라고 한다 | `file_space_put` 이 성공하고 평가 전용 사용자의 보관함에 그 대화의 `vault_origin` 행이 생긴다 | 보관본, 대화 |
| 그림 만들기 | 단순한 그림 하나를 만들라고 한다 | 답 메시지에 그림 결과물이 묶인다 | 결과물, 대화 |
| 기억 읽기 | 미리 넣은 기억을 물어본다 | 답에 미리 넣은 사실의 낱말이 든다 | 대화. 미리 넣은 한 줄은 남긴다 |
| 예약 작업 발화 | 문항을 보내지 않는다. 실행기가 `TaskService` 로 평가 전용 사용자의 `TURN` 작업을 한 번(`ONCE`, 지금 뒤 1분. `ONCE` 는 지금보다 뒤여야 한다)으로 만들어 발화기에 맡긴다. 끝남 조건은 그 작업의 `task_run` 이 끝난 상태가 된 것이다. 지시는 짧은 고정 글이다 | 그 작업의 `task_run` 이 `SUCCEEDED` 로 끝나고 `execution_id` 가 찬다 | 예약 작업과 그 대화 |
| 원본 사진 조회 | 앞 메시지에 합성 사진(심은 글자)을 올리고 다음 메시지에서 글자를 묻는다 | 답에 심은 글자가 들고, 둘째 turn 트리에 `attachment_inspect` 의 `TOOL_COMPLETED` 가 실패 없이 있다. `inspected` 단계 사건은 로그에만 남아 Control Plane 이 읽지 못한다(`MediaPipelineEvents`) | 첨부, 대화 |

합성 사진은 `test/model-eval/blog/make_photos.py` 와 같은 방식으로 만든 파일 하나를 `backend` 리소스로 둔다.
확인 시작 뒤 평가 사용자에게 생긴 기억 가운데 미리 넣은 한 줄이 아닌 것은 확인이 끝날 때 기능의 통과와 상관없이 지운다(문항이 돌다 `memory_remember` 로 생길 수 있다). 「정리」 는 통과한 기능에만 바로 한다. 실패한 기능의 대화와 만든 것은 30일 뒤 정리 작업이 지운다(스펙 DECISION16, DECISION19). 정리 작업은 하루 한 번 돌고, 대화를 지운 뒤 `quality_run.result_json` 의 `conversationPublicId` 와 `executionIds` 를 비운다. 그래서 30일 뒤에는 화면에 링크가 없다. 줄과 숫자는 남는다. 실행 기록 줄 자체는 기존 실행 기록 보존(rule-49)을 따른다.

### 실패 알림

새 `NotificationKind.QUALITY_CHECK_FAILED`. 받는 사람은 기존 관리자 알림 관례(`DEFAULT_TOOLS_PENDING` 등)와 같이 그룹의 차단되지 않은 `ADMIN` 이다. `target_type` 은 새 값 `ADMIN_QUALITY` 이고 목록 화면을 가리키는 종류라 `target_public_id` 를 비운다. 그래서 `NotificationTargetType.list()`, data-schema 의 `notification` 절(목록 화면 종류 목록), 웹의 알림 이동 대상, `docs/features/attention.md` 의 알림 종류 표를 같은 PR 에서 고친다. `source_public_id` 는 `quality_run.public_id` 다. `quality_run` 을 `FAILED` 로 바꾸는 트랜잭션에서 알림을 함께 만든다(알림과 그 원인은 한 트랜잭션). 본문은 실패한 기능 이름만 담는다. 다음 확인이 통과하면 앞 실패 알림을 `resolve` 로 읽음 처리하지 않는다. 실패 기록은 사람이 보고 지운다.

### 화면

`/admin/quality` 를 새로 만들고 첫 칸만 그린다. 나머지 칸은 PR2 와 PR3 이 더한다.
평가 전용 사용자가 없으면 칸 대신 「평가 사용자 준비」 단추와 한 줄 안내를 보인다.
확인 목록은 시각, 계기, 결과, 걸린 시간을 한 줄로, 펼치면 기능별 결과와 까닭을 보인다. 실패 기능은 `executionIds` 마다 관리자 품질 화면 안의 실행 트리 보기로 잇는다. 그 보기는 위 `admin.quality` 의 트리 조회를 쓴다. 30일이 지나 비워진 줄은 링크를 그리지 않는다.
관리자 메뉴 자리는 관리자 영역 재편 스펙을 따른다. 그 스펙이 아직 머지되지 않았으면 지금 관리자 메뉴 끝에 「품질」 로 더하고 재편 PR 이 옮긴다.

### 실행기 구현 메모

계획 검토(2026-11-20)에서 나온 실행기 쪽 제약이다. 실행기 PR 의 계획이 이 절을 따른다.

- **시작 잠금은 평가 사용자 행으로 한다.** `AppUserRepository.findByIdForUpdate(평가 사용자 번호)` 로 잠근 뒤 `RUNNING` 인 `DEPLOY_CHECK` 를 찾는다. `group_setting` 은 키마다 줄이라 잠글 줄이 없을 수 있다.
- **기능 결과는 기능이 끝날 때마다 `result_json` 에 더해 저장한다.** 도중에 재기동돼도 실패 기능의 대화 번호가 남아 30일 정리가 지울 수 있다. 재기동 때 끝내지 못한 기능은 `FAILED`, 까닭 `INTERRUPTED` 로 적고 줄을 `FAILED` 로 끝낸다.
- **조회 값은 `quality/application/dto` 의 record 로 돌려준다.** `admin` 은 기능의 `domain` 을 쓰지 못한다(`ADMIN_USES_ONLY_FEATURE_APPLICATION`).
- **보관함 판정과 정리에 쓸 조회를 `chat.artifact` 에 더한다.** 지금 `ArtifactVaultService.list` 의 항목에는 대화 번호와 `vault_origin` 이 없다. 그 대화로 `vault_origin` 이 있는 보관 행 번호를 돌려주는 조회를 둔다.
- **예약 작업 발화 기능은 결과와 상관없이 끝날 때 작업을 보관 처리한다.** 시간 초과로 끝나도 발화하지 않은 `ONCE` 작업이 뒤 기능 도중에 발화하지 않게 한다. 작업 입력은 알림 `NotifyPolicy.NEVER`, 시간대 `Asia/Seoul`.
- **위임 기능은 `send` 가 돌아온 뒤 트리에 위임 자식이 없으면 바로 `NO_CHILD` 로 끝낸다.** 10분을 기다리지 않는다.
- **설정은 `application.yml` 에 `${ASSISTANT_QUALITY_FEATURE_TIMEOUT:10m}` 꼴 자리를 둔다.** e2e 가 환경 변수로 줄인다.
- **통합 테스트가 실행기를 끝까지 돌리지 않게 `start` 를 「줄 만들기」 와 「띄우기」 로 나눈다.** 끝내기와 알림은 따로 부를 수 있는 메서드로 두고 직접 테스트한다. 마감 값은 `LiveProperties` 로 읽어 테스트에서 줄일 수 있게 한다.
- **e2e 시나리오는 `test/e2e/run.ts` 의 맨 끝에 등록한다.** 사용자를 하나 더 만들어 앞 시나리오의 사람 수 세기를 깨지 않게 한다.
- **화면 PR 전까지 알림의 이동 대상 `/admin/quality` 는 없는 화면이다.** 실행기 PR 과 화면 PR 을 같은 배포에 묶거나, 화면 PR 이 먼저 머지되게 한다.
- 까닭 코드에는 기능 규칙의 코드와 함께 `BUSY`, `INTERRUPTED`, `RUN_FAILED`, `TIMED_OUT` 을 둔다.

## 실패와 일관성

| 상황 | 처리 |
| --- | --- |
| 확인을 두 번 시작 | 도는 `DEPLOY_CHECK` 를 그대로 돌려준다. 시작은 평가 사용자 행 잠금(`AppUserRepository.findByIdForUpdate`) 안에서 `RUNNING` 인 `DEPLOY_CHECK` 를 찾아 하나만 만든다(「실행기 구현 메모」) |
| `USER_BUSY`, `CONVERSATION_BUSY` | 평가 전용 사용자는 실행기만 쓰므로 정상이면 일어나지 않는다. 일어나면 그 기능을 `FAILED`, 까닭 `BUSY` 로 적는다 |
| Hermes 연결 실패, 시간 초과, 실행 실패 | 그 기능 `FAILED`, 까닭은 실행 줄의 오류 종류를 고정 코드로 옮긴다 |
| 정리 실패 | 결과는 바꾸지 않는다. 경고 로그를 남기고 하루 정리 작업이 다시 지운다 |
| 서버 재기동 | 「확인 하나는 서버 하나에서 돈다」 대로 `INTERRUPTED` |
| 배포 순서 | 마이그레이션(칸과 표)과 코드가 한 PR 이다. 파이프라인 요청은 이 PR 이 배포되고 평가 사용자를 준비한 뒤에 켠다 |

## 검증 전략

| ID | 경계와 환경 |
| --- | --- |
| REQ1 | 기능별 판정 함수는 단위 테스트(통과, 실패, 경계). 실행기 전체는 가짜 Hermes e2e(`test/e2e/`)에서 기능 하나가 통과하고 하나가 실패하는 시나리오로 상태 전이, `result_json`, 정리를 본다. 실제 모델이 문항을 따르는지는 배포 뒤 운영에서 확인 한 번을 돌려 본다 |
| REQ2 | 사용자 목록, 에이전트 목록, 멘션 후보, 사용량 집계의 조회 테스트가 평가 전용 사용자를 빼는지 본다. 실행기가 다른 사용자의 데이터를 바꾸지 않는지는 e2e 에서 다른 사용자의 기억·파일·예약 수를 전후로 센다 |
| REQ3 | 실패로 끝난 확인이 `ADMIN` 마다 알림 한 줄을 남기는지 통합 테스트. 파이프라인 알림은 운영 저장소 몫이다 |
| REQ4 | 관리자 API 의 권한(관리자만)과 응답 모양은 통합 테스트, 화면 첫 칸은 브라우저 테스트 |

## PR1 나눔

PR1 은 운영 코드 상한을 넘어 셋으로 나눈다. 각 PR 이 그 단계의 설계와 구현을 함께 담는다.

| PR | 범위 | 남은 확인 |
| --- | --- | --- |
| 기반 | `app_user.evaluation`, `quality_run`, 평가 전용 사용자 준비와 관리자 API, 숨기는 곳, 기능별 규칙 판정(REQ1 의 판정, 사용량 「평가」 한 줄을 뺀 REQ2) | 배포하고 아래 요청 5 를 마친 뒤 관리자가 평가 사용자를 준비해 상태에 빠진 것이 없고, 관리자 사용자 목록에 「평가용」 줄이 하나인지 본다 |
| 실행기 | 확인 시작·조회 API, 기능 차례 실행과 10분 마감, 실패 알림, 30일 정리, 가짜 Hermes e2e(REQ1, REQ3) | 배포 뒤 운영에서 확인 한 번을 돌려 본다 |
| 화면 | `/admin/quality` 첫 칸과 사용량 「평가」 한 줄(REQ4, REQ2 의 나머지). 실행기 PR 과 같은 배포에 묶는다 | 브라우저 테스트 |

## 운영 저장소에 넘길 요청

이 PR 이 배포된 뒤 `fos-home-infra` 에 요청한다. 접속 값과 토큰을 만드는 방법은 그쪽이 갖는다.

1. 배포가 끝나고 Control Plane 이 응답하면 관리자 토큰으로 `POST /api/v1/admin/quality/checks` 를 부른다.
2. `GET /api/v1/admin/quality/checks/{publicId}` 를 1분마다 부르고, `status` 가 `PASSED` 나 `FAILED` 가 되면 멈춘다. 기다리는 상한은 기능 수에 10분을 곱하고 10분을 더한 값이다. 넘거나 Control Plane 이 응답하지 않으면 실패로 본다.
3. `FAILED` 나 실패로 본 경우 파이프라인 알림을 보낸다. 되돌리기는 하지 않는다.
4. 평가 전용 사용자의 Hermes profile 은 Control Plane 이 만든다. 운영 저장소는 profile 을 따로 만들지 않는다.
5. 평가 사용자를 준비하기 전에 그 profile 을 실행 공간 정책과 첨부(`vision`) 정책에 등록한다. 그림 만들기와 사진 조회 도구는 등록된 profile 에서만 켜진다(ADR-20261119 / image-gen-defaults, ADR-091). 공유 gateway 를 다시 띄우는 배포 단계가 그 profile 도 포함하는지 확인한다.

## PR2a: 평가 사용자로 묶음 문항 돌리기

스펙 REQ5, REQ6, REQ9 의 「지금 돌리기」, REQ15 부터 REQ18 까지를 설계한다. PR1 의 실행기(`DeployCheckRunner`, `DeployCheckObserver`, `DeployCheckCleaner`)가 머지된 뒤 그 위에 얹는다.

### 책임과 경계

| 모듈 | 맡는 것 |
| --- | --- |
| `backend` 의 `followup` | 평가 사용자의 한 대화가 만든 할 일을 지우는 메서드. 지금은 닫기와 사용자 전체 정리만 있다 |
| `backend` 의 `quality` | 묶음 파일 읽기, 평가 한 번(`kind` `EVAL`)의 시작과 상태 전이, 문항 실행, 규칙 판정, 묶음 결과, 문항마다 만든 것의 기록과 비우기, 출처 링크 열어 보기 |
| `backend` 의 `admin.quality` | 평가 실행 API 와 평가 사용자 대화 읽기. `quality.application` 만 부른다 |
| `backend` 의 `shared` | 공개 주소로만 여는 HTTP 읽기(주소 검사, 이름 풀이 뒤 공개 IP 판정, 풀어 낸 IP 에 고정한 연결, 넘겨주기마다 다시 검사)를 `chat.artifact.infra` 의 `LinkImageFetcher` 와 `ArtifactSourceFetcher` 에서 꺼내 둔다. 두 그림 받기와 출처 링크 열기가 함께 쓴다 |
| `web` | `/admin/quality` 둘째 칸(평가 기록)과 「지금 돌리기」 |
| `scripts/dev/run-eval.ts` | 코디네이터가 관리자 토큰으로 평가를 시작하고 끝날 때까지 기다려 결과를 보이는 스크립트. 기능 완료 증거에 붙일 글을 낸다 |

**평가 실행기는 배포 뒤 확인 실행기와 따로 두고 아래 부품을 함께 쓴다.** 문항을 별도 작업에서 `send` 로 보내고 끝남 조건을 보는 부분, 마감이 지나면 turn 과 위임 자식을 멈추는 부분, 관찰(`DeployCheckObserver`)과 대화 지우기(`DeployCheckCleaner.dropConversation`)다. 함께 쓰는 부분은 PR2a 가 `quality.application` 안에서 이름을 바꿔 꺼낸다(배포 뒤 확인 전용 이름이 아니게). 확인은 기능이 고정이고 평가는 문항이 파일에서 오므로, 한 실행기에 넣으면 기능 분기와 문항 분기가 섞인다.

**문항은 사람과 같은 길로 보낸다.** `ChatService` 의 대화 만들기, 모델 단계 고르기, `send`, 첨부 올리기와 `ConnectorActionService` 의 승인·거절을 평가 사용자의 `CurrentUser` 로 부른다. 평가만을 위한 판정이나 허락은 두지 않는다. `quality` 는 층 순서에서 `chat`, `connector`, `task`, `followup`, `memory` 위라 모두 부를 수 있다.

### 묶음 파일

`backend/src/main/resources/quality/bundles/<묶음 id>.json` 하나가 묶음 하나다. 읽는 자리는 설정 `assistant.quality.bundle-location`(기본 `classpath:quality/bundles/`)이고, e2e 는 테스트 묶음 폴더로 바꾼다. 새 묶음은 파일을 더해 만들고 실행기를 고치지 않는다(REQ5). 기동 때 모두 읽어 형식을 검사하고, 틀린 파일이 있으면 기동을 멈춘다. 저장소 테스트가 같은 검사를 CI 에서 돌린다.

| 칸 | 뜻 |
| --- | --- |
| `id`, `title` | 묶음 id(파일 이름과 같다)와 화면에 보일 이름 |
| `timeoutMinutes` | 문항 하나의 상한. 없으면 10(스펙 DECISION40) |
| `pass` | 묶음 통과 규칙. `{"type":"ratio","ratio":0.85}` 는 통과 문항 비율, `{"type":"answer-quality"}` 는 지금 `answer/score.ts` 의 `scoreSet` 기준(출처 15/17 비율, 열린 링크 90%, 날짜 문항, 사진 요청 문항). 없으면 `ratio` 0.85 |
| `questions[]` | 문항. 아래 표 |

| 문항 칸 | 뜻 |
| --- | --- |
| `id` | 묶음 안에서 유일 |
| `turns[]` | 차례. 각 차례는 `text`(보낼 글), 선택 `attachment`(리소스 사진 이름), 선택 `card`(`approve`, `reject`, `leave`) |
| `expect` | 규칙 판정. 서비스 개념의 기대(`tools`, `links`, `linkPrefixes`, `noLinkPrefixes`, `includes`, `absent`, `instructionIncludes`)와 답변 품질의 `check`(`sources`, `date`, `none`, `photo-request`), 카드 결과 `cardStatus`(예: `SUCCEEDED`) |
| `keepConversation` | 통과해도 대화를 30일 남긴다. 답변 품질의 사람 비교 문항(스펙 DECISION38) |

옛 `questions.json` 의 문항은 `messages` 를 `turns` 로 바꿔 그대로 옮긴다. 「답변 품질」 묶음은 `timeoutMinutes` 15, `pass` `answer-quality` 이고, 「서비스 개념」 묶음은 `ratio` 0.85 다.
문항의 자리(`{otherAgentCode}`, `{otherAgentName}`, `{connectorId}`, `{connectorTitle}`)는 실행기가 평가 사용자의 위임 대상 에이전트와, 그룹 카탈로그에서 평가 사용자가 연결하지 않은 첫 플러그인으로 채운다. 값이 없으면 그 문항은 「건너뜀」 이고 분모에서 빠진다(스펙 DECISION35).

### 평가 한 번의 흐름

1. 시작은 평가 사용자 행 잠금(`AppUserRepository.findByIdForUpdate`) 안에서 `RUNNING` 인 `EVAL` 줄을 찾는다. 있으면 새로 만들지 않고 그 줄을 돌려준다(REQ9). `DEPLOY_CHECK` 는 보지 않는다(스펙 DECISION23).
2. 「비우기 끝남」 이 없는 끝난 `EVAL` 줄의 문항 대화를 다시 비운다(「비우기」).
3. 고른 묶음의 문항을 파일 순서대로 하나씩 돈다. 문항마다 새 대화를 만들고, 모델 단계를 골랐으면 그 대화의 단계를 바꾼 뒤 차례를 보낸다. 고르지 않았으면 평가 사용자에게 걸리는 기본 단계(`ModelTierService` 의 사용자 기본, 없으면 그룹 기본)이고, 실행 시작 때 실제로 쓴 단계를 `result_json.tier` 에 적는다.
4. 문항이 끝나면 판정하고 결과를 `result_json` 에 더해 저장한다. 사용자 실행 한도로 거절되면 PR1 처럼 `FAILED`, 까닭 `BUSY` 다. 판정 직후 그 문항이 만든 예약 작업을 지우고 남긴 카드를 거절한다. 실행 도중 발화해 비용이 들고 사용자 실행 자리를 잡지 않게 한다. 통과했고 `keepConversation` 이 아니면 그 뒤 대화를 지운다.
5. 모두 끝나면 묶음 결과를 계산해 `PASSED`(모든 고른 묶음이 통과)나 `FAILED` 로 끝내고, 문항이 만든 나머지를 지운다.

사용자 실행 한도(`max-running` 4)는 평가 한 문항(위임이나 승인 뒤 다시 도는 turn 이면 둘)과 함께 도는 배포 뒤 확인(위임이면 둘)을 받는다. 평가가 만든 예약 작업은 판정 직후 지워 발화가 자리를 잡지 않는다. 그래서 평가는 문항을 차례로 돌고 확인과는 겹쳐도 된다. 겹쳐 `USER_BUSY` 가 나면 PR1 처럼 그 문항을 `BUSY` 로 적는다.
마감과 멈춤은 PR1 의 「기능마다 10분 마감」 과 같고 상한만 묶음 값이다. 넘으면 그 문항만 `TIMED_OUT` 이다. 테스트와 e2e 는 설정 `assistant.quality.eval-timeout-override`(기본 없음)로 모든 묶음의 상한을 줄인다.
재기동 때 `RUNNING` 인 `EVAL` 줄은 `FAILED`, 까닭 `INTERRUPTED` 로 끝낸다. 끝내지 못한 문항은 실패로 적어 대화를 30일 남긴다(스펙 DECISION37).

### 여러 차례와 승인 카드

차례마다 `send` 가 돌아온 뒤 그 대화의 `connector_action` 가운데 이번 차례가 만든 `PENDING` 줄을 찾는다(`DeployCheckObserver` 가 이미 읽는 조회). `card` 가 `approve` 면 `ConnectorActionService.approve(평가 사용자, actionId, null, false)`, `reject` 면 `reject` 를 부른다. 화면 단추와 같은 서비스다(`ConnectorActionController`). 원고 저장 승인처럼 다른 길로 가는 줄은 평가 문항에 쓰지 않는다.
`card` 를 적지 않았는데 카드가 뜨면 `leave` 로 본다. 남긴 카드는 판정 직후, 대화를 지우기 전에 거절한다(스펙 REQ16).
카드를 누르면 결과가 대화로 가고 에이전트가 한 번 더 돌 수 있다(`ConnectorActionListener`, `NextTurnDispatcher`). 그래서 다음 차례는 위임과 같은 끝남 조건(대화에 도는 turn 이 없고 `ChatService.deliveryStates` 의 전달이 끝남)을 기다린 뒤 보낸다. 이 기다림도 문항 상한 안이다. 판정은 문항의 모든 루트 실행 트리와 마지막 답을 본다. `expect.cardStatus` 는 마지막으로 누른 줄의 상태를 본다.
카드 줄이 원고 저장 승인(`ContentDraftSaveApproval.select` 가 고르는 줄)이면 누르지 않고 그 문항을 `FAILED`, 까닭 `CARD_FAILED` 로 적는다. 화면은 그 줄을 다른 길로 승인하기 때문이다.

### 판정

`test/model-eval/concepts/score.ts` 와 `test/model-eval/answer/score.ts` 의 판정을 Java 의 순수 함수로 옮긴다. 답변 품질은 사진 요청 판정(`photo-request`)과 답 형식 검사(`formatCheck`)까지 옮긴다. 사람 비교 결과를 받는 `--compare` 는 옮기지 않는다. 사람 판정을 적을 자리는 PR2b(REQ8)가 만들고, 그때까지 답변 품질 묶음은 규칙 기준을 넘으면 지금처럼 「사람 비교 대기」 로 보인다. 옮긴 판정은 같은 입력에 같은 결과를 내는지 옛 테스트의 사례를 그대로 단위 테스트로 옮겨 본다(REQ6).
출처 링크 열어 보기는 GET 으로 열고 본문은 읽지 않는다. `shared` 로 꺼낸 공개 주소 읽기를 쓴다. 호스트를 풀어 공개 IP 일 때만 그 IP 에 고정해 연결하고, 넘겨주기(redirect)마다 다시 본다. 이름 풀이를 바꿔치기해 내부 주소로 가는 것을 막는다. 열지 못하면 옛 판정처럼 「확인 못 함」 이고 비율에서 뺀다.
`instructionIncludes` 는 답이 링크한 예약 작업의 지시문을 `TaskService` 로 읽어 본다.

### 저장: `quality_run` 의 `EVAL`

PR1 의 표를 그대로 쓴다. 새 칸은 없다.

| 칸 | `EVAL` 에서 |
| --- | --- |
| `kind`, `trigger_type` | `EVAL`, `ADMIN`. PR2b 가 `SCHEDULED` 를 더한다 |
| `result_json` | `{tier, bundles[], items[]}`. `bundles` 는 묶음마다 `id`, `status`, 통과 수, 문항 수, 건너뜀 수, 답변 품질 기준 숫자. `items` 는 문항마다 `bundle`, `question`, `status`(`PASSED`, `FAILED`, `TIMED_OUT`, `SKIPPED`), `reasonCode`, `elapsedMs`, 규칙 숫자, `conversationPublicId` 와 `executionIds`(실패나 `keepConversation` 만, 30일), `created`(만든 것의 번호) |

`result_json` 의 모양이 `kind` 마다 다르다. 지금 `QualityRun.results` 는 `FeatureResultsConverter` 로 `List<FeatureResult>` 에 묶여 있고 JPA 변환기는 `kind` 를 보지 못한다. 그래서 엔티티 칸을 JSON 문자열로 두고, `kind` 별 읽기·쓰기는 domain 의 codec 두 개(지금 `FeatureResultsConverter` 를 JPA 변환기에서 정적 codec 으로 바꾼 것과 새 `EvalResult` codec)가 한다. 지금 변환기처럼 codec 안의 정적 `JsonMapper` 를 쓴다. 엔티티는 `kind` 가 맞지 않는 읽기·쓰기를 거절한다. `item_count` 는 `EVAL` 에서 고른 문항 수이고 PR1 의 `record` 처럼 결과 수로 덮지 않는다. 답 본문과 질문 본문은 담지 않는다.

**PR1 코드도 `kind` 를 본다.** 재기동 처리(`DeployCheckRuns.interruptRunning`)와 하루 정리(`DeployCheckCleaner.sweepExpired`)는 지금 `kind` 를 가리지 않는다. 그대로면 도는 `EVAL` 줄에 배포 기능을 채우고 배포 뒤 확인 실패 알림을 보내며, `EVAL` 줄을 `FeatureResult` 로 읽다 멈춘다. 두 곳을 `kind` 로 나누고, 하루 정리는 통과한 `EVAL` 줄의 `keepConversation` 대화도 30일 뒤 지운다.

### 비우기

**문항이 만든 것은 그 문항의 대화 번호로 찾는다.** 평가 사용자의 대화는 문항마다 새로 만들므로 대화 번호가 문항을 가린다. 함께 도는 배포 뒤 확인의 것은 대화가 달라 섞이지 않는다.

| 만든 것 | 찾는 길 | 지우는 길 |
| --- | --- | --- |
| 예약 작업 | 루트 실행이 만든 것은 `task.conversation_id`(`task_create` 가 원래 대화를 넣는다, `TaskAgentRequests`). 위임 자식이 만든 것은 대화 칸이 비므로, 그 문항이 도는 동안 생긴 평가 사용자의 대화 칸이 빈 작업 가운데 배포 뒤 확인의 고정 지시문이 아닌 것 | 판정 직후 보관 처리한다 |
| 기억 | 그 대화의 `memory_capture`(`MemoryCaptureService.capturesOf`) | `CREATED` 와 `PROPOSED` 는 그 기억을 지우고, `UPDATED` 는 늦은 것부터 되돌린다(`undo`). 준비 때 넣은 기억이 고쳐졌으면 되돌아온다 |
| 할 일 | `follow_up.conversation_id` | `followup` 에 더하는 대화 단위 지우기 |
| 승인 줄 | `connector_action.conversation_id` | 기다리는 줄을 거절로 닫는다. 승인 줄은 감사 기록이라 줄 자체는 남는다(`ConnectorConversationActionPurge`) |
| 보관본 | PR1 의 `ArtifactVaultFinder.putIdsOf` | 판정 직후 통과·실패와 상관없이 지운다(스펙 REQ17) |
| 대화와 결과물 | 문항 대화 | 통과하면 바로, 실패와 `keepConversation` 은 30일 뒤(PR1 의 `dropConversation`) |

문항이 시작할 때 대화 번호를 `result_json` 의 그 문항에 먼저 적는다. 끊겨도 무엇을 지울지 남는다.
실행 끝에 모든 문항의 대화로 위 표를 지우고 줄에 「비우기 끝남」 을 적는다. 대화와 결과물은 통과하면 바로, 실패와 `keepConversation` 은 하루 정리가 30일 뒤 지운다.
「비우기 끝남」 이 없는 끝난 `EVAL` 줄(끊김, 비우기 실패)은 재기동 처리, 다음 실행 시작, 하루 정리가 다시 지운다(스펙 DECISION37). 그래서 수동 실행이 다시 없어도 남은 예약 작업이 하루를 넘겨 발화하지 않는다.

**PR1 배포 뒤 확인의 기억 지우기도 대화로 바꾼다.** 지금은 확인 시작 뒤 생긴 기억을 모두 지워(`DeployCheckCleaner.forgetNewMemories`), 함께 도는 평가 문항이 막 만든 기억까지 지운다. 확인 기능의 대화의 `memory_capture` 로 지우게 바꿔 서로의 것을 건드리지 않는다.

### 평가 실행 API

| 요청 | 응답 |
| --- | --- |
| `GET /api/v1/admin/quality/eval-bundles` | 묶음 목록(`id`, `title`, 문항 수, 상한) |
| `POST /api/v1/admin/quality/evals` | 본문 `{bundles: [id...], tier}`. `bundles` 가 비면 전체, `tier` 가 없으면 평가 사용자에게 걸리는 기본 단계(스펙 DECISION41). 202 와 새 줄. 도는 평가가 있으면 새로 만들지 않고 200 과 그 줄(PR1 의 확인 시작과 같다). 화면과 스크립트는 200 이면 「평가가 돌고 있다」 를 보인다. 평가 전용 사용자가 없으면 409 `EVALUATION_USER_MISSING`. 없는 묶음 id 는 400 `QUALITY_EVAL_BUNDLE_UNKNOWN`, 모르는 단계는 400 |
| `GET /api/v1/admin/quality/evals?cursor=` | 최근 순 목록. 품질 화면 둘째 칸 |
| `GET /api/v1/admin/quality/evals/{publicId}` | 한 줄과 묶음 결과, 문항 결과 |
| `GET /api/v1/admin/quality/conversations/{publicId}/messages` | 평가 사용자의 대화만 연다. 남겨 둔 실패 대화와 사람 비교 문항의 답을 관리자가 읽는다. 다른 사용자의 대화는 404 다(PR1 의 트리 조회와 같은 이유, 이 문서 DECISION8) |

### 화면

`/admin/quality` 둘째 칸에 평가 목록을 그린다. 줄마다 시각, 고른 묶음, 모델 단계, 결과, 걸린 시간이다. 펼치면 묶음 결과와 문항 결과, 실패와 사람 비교 문항의 대화 보기(위 메시지 API)와 실행 트리 보기(PR1)로 잇는다.
「지금 돌리기」 는 묶음을 여럿 고르고(기본 전체) 모델 단계를 고르는 작은 창이다. 도는 평가가 있으면 단추를 막고 「평가가 돌고 있다」 와 그 줄로 가는 길을 보인다.

### 기능 PR 의 배포 뒤 확인

- 기능 PR 은 `quality/bundles/` 에 자기 기능 묶음 파일을 더하거나 문항을 더한다. 묶음 id 는 기능 폴더 id 를 쓴다.
- 배포 뒤 코디네이터가 `scripts/dev/run-eval.ts --bundle <id>` 로 돌린다. 스크립트는 관리자 API 로 시작하고 끝날 때까지 기다린 뒤 묶음 결과와 문항 결과 표를 낸다. 종료 코드는 0 통과, 1 미달, 2 실행 오류다. 토큰과 주소는 환경 변수로만 받고 출력하지 않는다.
- 루트 `AGENTS.md` 의 「Hermes 연동을 바꿨으면 배포 뒤 왕복시켜 본다」 를 「기능 묶음을 돌려 본다」 로 고치고, 묶음으로 못 하는 확인만 사람 계정으로 한다는 예외를 적는다(스펙 REQ18).
- 서비스 개념 스펙 REQ6, DECISION33 과 설계 「평가 세트」, `docs/features/answer-quality.md` 의 실행 방법을 이 길로 고친다.

### 실패와 일관성

| 상황 | 처리 |
| --- | --- |
| 평가를 두 번 시작 | 평가 사용자 행 잠금 안에서 `RUNNING` 인 `EVAL` 을 찾아 하나만 만들고, 둘째 요청에는 200 과 그 줄을 돌려준다 |
| 함께 도는 배포 뒤 확인 | 막지 않는다. 둘 다 자기 대화 번호로 만든 것을 찾아 서로의 것을 지우지 않는다 |
| 묶음 파일 형식 오류 | 기동을 멈춘다. CI 의 저장소 테스트가 먼저 막는다 |
| 링크 열기 실패, 시간 초과 | 그 링크는 「확인 못 함」. 문항 판정은 계속한다 |
| 승인 호출 실패 | 그 문항 `FAILED`, 까닭 `CARD_FAILED` |
| 비우기 실패 | 결과는 바꾸지 않고 경고 로그. 「비우기 끝남」 이 없어 재기동 처리, 다음 시작, 하루 정리가 다시 지운다 |
| 서버 재기동 | `INTERRUPTED`. 재기동 처리가 바로 남은 것을 지운다 |
| 카드가 원고 저장 승인 줄 | 누르지 않고 `CARD_FAILED` |

### 검증 전략

| ID | 경계와 환경 |
| --- | --- |
| REQ5 | 묶음 파일 형식 검사와 묶음 고르기는 단위·통합 테스트. 새 묶음 파일 하나를 테스트 리소스로 더해 실행기를 고치지 않고 도는지 본다 |
| REQ6 | 옮긴 판정 함수에 옛 `score.ts` 테스트 사례를 그대로 옮겨 같은 결과를 본다. 운영에서 PR2a 배포 뒤 두 묶음을 한 번 돌려, 옛 스크립트의 마지막 결과와 묶음 결과가 크게 다르지 않은지 사람이 본다. 그 뒤 옛 스크립트를 지운다 |
| REQ9 | 「지금 돌리기」 의 중복 시작 409 와 모델 단계 적용은 통합 테스트, 화면은 브라우저 테스트 |
| REQ15 | 가짜 Hermes e2e 로 평가 한 번을 돌리고, 다른 사용자의 기억·할 일·예약 작업·대화 수가 전후로 같은지 센다 |
| REQ16 | 카드를 띄우는 가짜 Hermes 응답으로 `approve`, `reject`, `leave` 차례를 e2e 로 돌려 승인 줄 상태와 `cardStatus` 판정을 본다 |
| REQ17 | 통합 테스트로 문항의 대화로 만든 예약 작업·기억·할 일·승인 줄이 지워지거나 닫히는지, 준비 때 넣은 기억을 고친 문항 뒤 되돌아오는지, 배포 뒤 확인의 대화로 만든 것은 남는지, 「비우기 끝남」 이 없는 줄을 재기동과 하루 정리가 지우는지 본다. PR1 의 재기동 처리와 하루 정리가 `EVAL` 줄을 건드리지 않는지도 본다 |
| REQ18 | `run-eval.ts` 의 인자와 종료 코드는 단위 테스트. 문서 규칙은 리뷰가 본다 |

### PR2a 나눔

운영 코드 상한(1500줄)을 넘을 것이므로 셋으로 나눈다(나누는 선과 순서는 사용자 2026-11-21). 각 PR 이 설계와 구현을 함께 담는다. 사용자 기억을 바꾼 서비스 개념 평가를 먼저 평가 사용자로 옮긴다.

| PR | 범위 | 남은 확인 |
| --- | --- | --- |
| 평가 실행기와 서비스 개념 묶음 | 저장 형식의 `kind` 구분과 PR1 의 재기동·하루 정리·기억 지우기 변경, 묶음 파일 읽기, 서비스 개념 판정 옮기기와 그 묶음 파일, 카드 처리, 비우기, 평가 실행 API(REQ5, REQ6 의 서비스 개념, REQ9 API, REQ15 부터 REQ17 까지) | 배포 뒤 서비스 개념 묶음을 한 번 돌려 옛 스크립트의 마지막 결과와 비교한다 |
| 답변 품질 묶음 | 답변 품질 판정(형식 검사, 사진 요청 포함) 옮기기와 그 묶음 파일, `answer-quality` 통과 규칙, `keepConversation`, 공개 주소 읽기 꺼내기와 링크 열기, `run-eval.ts`(REQ6 의 답변 품질, REQ18 의 스크립트) | 배포 뒤 답변 품질 묶음을 한 번 돌려 옛 결과와 비교한다 |
| 화면과 정리 | 품질 화면 둘째 칸과 「지금 돌리기」, 옛 TypeScript 평가 스크립트 지우기(`test/model-eval/answer/`, `test/model-eval/concepts/`. 블로그 평가가 쓰는 `control-plane.ts` 는 남긴다), `AGENTS.md` 와 서비스 개념·답변 품질 문서 고치기(REQ6 의 지우기, REQ9 화면, REQ18) | 브라우저 테스트 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 결과를 저장할 곳 | 새 테이블 `quality_run` 하나. PR2 정기 평가와 PR3 문항 후보도 이 표와 JSON 칸으로 받는다 | 사용자 2026-11-20 | 확인 한 번은 예약 작업과 수명과 뜻이 다르다(data-schema 「테이블을 추가하는 기준」 의 「본체와 수명이 다를 때」). `task_run` 은 예약 작업 줄과 예정 시각 유일 제약에 매여 가짜 작업 줄이 필요하고, `decision_feedback_event` 는 365일 뒤 지워진다 |
| DECISION2 | 평가 전용 사용자의 표시 | `app_user.evaluation` 컬럼 | 작성자 | 사용자당 참/거짓 하나는 본체 컬럼에 둔다(data-schema). 역할 값에 넣으면 권한 판정 코드가 모두 새 값을 알아야 한다 |
| DECISION3 | 평가 전용 사용자를 만드는 길 | 관리자가 품질 화면에서 한 번 준비한다. Control Plane 이 사람 더하기 흐름을 다시 쓴다. 실행 공간과 첨부 정책 등록만 운영 저장소가 먼저 한다 | 작성자 | 사람 더하기를 Control Plane 이 끝내는 기존 결정(ADR-018)과 같은 길이다. 정책 등록은 Control Plane 이 하지 못한다(`AgentPendingToolsets`) |
| DECISION4 | 파이프라인의 인증 | 기존 관리자 토큰. 새 인증 경계를 만들지 않는다 | 작성자 | 답변 품질 평가가 이미 운영 저장소가 만든 토큰으로 Control Plane 을 부른다. 서비스 토큰을 새로 두면 비밀값과 필터가 하나 더 는다 |
| DECISION5 | 기능을 도는 방식 | 차례로, 기능마다 10분 | 작성자 | 사용자 실행 한도에 닿지 않고 실패 원인이 서로 섞이지 않는다. 보통 수 분이면 끝난다 |
| DECISION6 | 문항과 판정의 자리 | 실행기 코드의 상수와 순수 판정 함수 | 작성자 | PR1 의 문항은 기능마다 하나라 파일 형식을 만들 까닭이 없다. PR2 의 묶음 파일 형식이 생기면 그때 옮긴다 |
| DECISION8 | 관리자가 평가 사용자의 실행을 여는 길 | `admin.quality` 에 평가 사용자 실행만 여는 트리 조회를 둔다 | 작성자 | 지금 실행 트리 조회는 주인만 연다. 모든 사용자의 실행을 여는 관리자 조회는 범위가 넓어 평가 사용자로 좁힌다 |
| DECISION7 | 재기동 때 도는 확인 | `INTERRUPTED` 로 끝내고 다시 돌리지 않는다 | 작성자 | 배포 자체가 재기동이라 다시 돌리면 같은 배포의 확인이 겹친다. 파이프라인이 실패로 보고 사람이 「다시 확인」 한다 |
| DECISION9 | 평가 실행기의 자리 | 배포 뒤 확인 실행기와 따로 두고 보내기·마감·관찰·대화 지우기만 함께 쓴다 | 작성자 | 확인은 기능이 고정이고 평가는 문항이 파일에서 온다. 한 실행기에 넣으면 두 분기가 섞인다 |
| DECISION10 | 묶음 파일의 자리와 형식 | `backend` 리소스의 묶음마다 JSON 파일 하나. 옛 `questions.json` 의 칸을 이어 쓰고 `messages` 를 `turns` 로 바꾼다 | 작성자 | 실행기가 Control Plane 안에 있어(스펙 DECISION17) 배포에 함께 실린다. 옛 칸을 이어 써 옮길 때 문항 글이 바뀌지 않는다 |
| DECISION11 | 만든 것을 가리는 방법 | 문항의 대화 번호로 찾는다. 예약 작업은 `task.conversation_id`, 기억은 `memory_capture`, 할 일과 승인 줄은 `conversation_id` 다. 위임 자식이 만든 예약 작업만 대화 칸이 비어 문항 시간 범위와 배포 뒤 확인의 고정 지시문으로 가린다. PR1 의 기억 지우기도 대화 방식으로 바꾼다 | 작성자 | 문항마다 대화가 새로 생겨 대화 번호가 문항을 정확히 가린다. 시각 비교는 함께 도는 배포 뒤 확인의 것을 지우고 고친 기억을 놓친다 |
| DECISION12 | 출처 링크를 여는 곳 | Control Plane 이 공개 IP 에 고정한 연결로만 연다. 그림 받기 두 곳의 공개 주소 읽기를 `shared` 로 꺼내 함께 쓴다 | 작성자 | 실행기가 Control Plane 안이라 서버가 열어야 한다. 모델이 쓴 주소를 서버가 열면 내부 주소로 갈 수 있어, 이미 검증한 판정과 IP 고정 연결을 다시 쓴다 |
| DECISION13 | 코디네이터가 평가를 부르는 길 | 관리자 토큰으로 관리자 API 를 부르는 `scripts/dev/run-eval.ts`(`scripts/README.md` 의 폴더 규칙) | 작성자 | 기존 관리자 토큰 경계(DECISION4)를 그대로 쓰고, 기능 완료 증거에 붙일 결과를 한 명령으로 낸다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| DECISION1 | data-model | 필요 없음. 예외 근거를 `backend/docs/data-schema.md` 의 `quality_run` 절에 적는다 | `backend/docs/data-schema.md` |
| 스펙 DECISION2 | 평가 정책 | PR2 에서 새 ADR 을 만든다(스펙 「결정 기록 연결」) | 스펙 DECISION2 |
| DECISION3, DECISION4 | architecture | 필요 없음. 기존 ADR-018 과 관리자 토큰 경계를 그대로 쓴다 | ADR-018, `ControlPlaneJwtFilter` |
