# 흐름

화면 전환과 호출 순서를 담는다.
모듈 배치는 [`code-architecture.md`](code-architecture.md), 저장 모델은 [`backend/docs/data-schema.md`](../backend/docs/data-schema.md)가 가진다.

## 두 방향과 두 토큰

요청이 한 방향으로만 흐르지 않는다.
Control Plane 이 Hermes 를 부르고, Hermes 가 다시 Control Plane 을 부른다.
**그 둘이 쓰는 토큰이 다르다.**

```mermaid
flowchart LR
    B["브라우저"]
    W["Next.js<br/>서버 라우트"]
    C["Control Plane"]
    H["Hermes<br/>API server"]
    M["모델"]

    B -->|"세션 쿠키"| W
    W -->|"①짧은 수명 JWT"| C
    C -->|"②API_SERVER_KEY<br/>POST /v1/runs"| H
    H --> M
    M -.->|"본문이 필요하다"| H
    H -->|"③agent_token<br/>POST /mcp"| C

    style C fill:#6D4AFF,color:#fff
    style H fill:#5A6B7C,color:#fff
```

| 번호 | 누가 누구를 | 토큰 | 그 토큰이 말하는 것 |
| --- | --- | --- | --- |
| ① | 웹 → Control Plane | 짧은 수명 JWT | 이 사람이 로그인했다 |
| ② | Control Plane → Hermes | `API_SERVER_KEY` | 이 profile 을 쓸 자격이 있다 |
| ③ | Hermes → Control Plane | `agent_token` | 이 요청이 어느 profile 에서 왔다 |
| ④ | 다른 서비스 → Control Plane | 서비스 토큰 | 이 요청이 어느 사용자의 문서를 읽을 수 있다 |

**①은 사용자를 정하고 ③은 profile 만 정한다.** 둘은 성질도 다르다.
①을 받을 때마다 Control Plane 이 그 사용자가 허용 목록에서 꺼졌는지 확인한다. 꺼졌으면 401 `ACCESS_REVOKED` 로 답하고 웹이 세션을 끊는다. 흐름은 [`backend/docs/flow.md`](../backend/docs/flow.md#사용자를-껐을-때) 의 「사용자를 껐을 때」 가 갖는다.
④는 사용자 한 사람과 받는 collection 을 정한다. 실행 없이 읽는 유일한 길이다. 그 사용자가 허용 목록에서 꺼지면 통하지 않는다.

| 축 | ① 웹 토큰 | ③ agent_token |
| --- | --- | --- |
| 만드는 때 | 요청마다 새로 | 한 번 발급하고 계속 씀 |
| 수명 | 짧다 | 폐기할 때까지 |
| 저장 | 저장하지 않는다 | 해시만 저장한다 |
| 두는 곳 | 만들어 바로 쓰고 버린다 | 홈서버 파일과 profile 의 `.env` |

②는 사용자를 정하지 않는다. profile 을 정할 뿐이다.
어느 사용자의 실행인지는 Control Plane 이 이미 알고 있고, 그것을 Hermes 에게 알리지 않는다.

Hermes 가 Control Plane 을 부를 때는 Control Plane 이 그 요청의 주인을 모른다.
**요청 본문에 사용자를 적게 하면 모델이 그것을 바꿀 수 있다.**
③도 사용자를 정하지 못한다. GROUP 에이전트는 여러 사용자가 같은 profile 을 쓰고, MCP 연결과 그 토큰은 profile 에 하나다.

그래서 사용자는 Control Plane 이 이미 기록한 실행에서 꺼낸다.
profile 플러그인이 도구 인자에 서명해 넣은 `_fos_ctx` 로 origin 실행 하나를 찾고, 그 실행의 `user_id` 가 요청자다. 하위 에이전트 session 은 만들 때 등록한 실행이, 최상위 session 은 지금 도는 실행이 origin 이다.
[`backend/docs/flow.md`](../backend/docs/flow.md#mcp-호출의-요청자를-정할-때) 의 「MCP 호출의 요청자를 정할 때」 가 그 흐름이다. 결정은 [ADR-032](../backend/docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 와 [ADR-037](../backend/docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) 에 있다.

## 로그인 활동 기록

```mermaid
sequenceDiagram
    participant W as NextAuth
    participant C as Control Plane
    W->>C: POST /api/v1/signin/allowed (signin 서명 토큰)
    C-->>W: 허용 여부
    alt 허용된 로그인
        W->>W: 세션 쿠키 준비
        W->>C: events.signIn에서 POST /api/v1/signin/completed
        alt 서명이 유효하고 허용 목록이 켜져 있음
            C->>C: last_login_at을 더 최근 시각으로 갱신
            C-->>W: 204
        else 서명 오류 또는 꺼졌거나 없는 주소
            C-->>W: 401 (기록하지 않음)
        end
        opt 기록 요청 실패
            W->>W: 오류 로그 (로그인은 계속)
        end
    else 로그인 거절
        W->>W: 세션과 활동 기록을 만들지 않음
    end
```

일반 요청과 세션 갱신은 로그인 활동을 기록하지 않는다.
관리자 목록은 기록이 없을 때 「기록 없음」을 보인다.
응답과 저장 계약은 [`backend/docs/flow.md`](../backend/docs/flow.md#관리자에게-보이는-최근-활동)가 갖는다.

## 대화 한 번

```mermaid
sequenceDiagram
    participant B as 브라우저
    participant W as Next.js 서버 라우트
    participant C as Control Plane
    participant H as Hermes

    B->>W: 메시지와 대화 식별자
    W->>W: 세션에서 메일 주소를 꺼내 짧은 수명의 토큰을 만든다
    alt 한 번에 받는 경로
        W->>C: POST /api/v1/chat/messages
    else 스트리밍 경로
        W->>C: POST /api/v1/chat/messages/stream
    end
    C->>C: 대화에 고정된 에이전트에서 profile 을 꺼낸다
    C->>C: 요청자가 볼 수 있고 그 에이전트가 받는 collection 의 Memory 만 골라 instructions 를 조립한다
    C->>C: 실행 한 줄을 RUNNING 으로 만든다
    C->>H: POST {profile}/v1/runs
    C->>C: 받은 run_id 를 그 줄에 적고 RUN_STARTED 를 남긴다
    opt 스트리밍 경로
        C-->>B: started 와 대화 식별자와 실행 번호
    end
    C->>H: GET {profile}/v1/runs/{id}/events
    loop 실행 중
        H-->>C: 답 조각과 도구와 하위 에이전트 사건
        C->>C: 도구와 하위 에이전트 사건을 execution_event 로 옮겨 적는다
        opt 스트리밍 경로
            C-->>W: delta, tool, subagent 사건
            W-->>B: 답 조각과 도구 상태
        end
    end
    C->>H: GET {profile}/v1/runs/{id} (종료 상태가 올 때까지 되풀이)
    alt Hermes 실행 성공
        C->>C: 메시지를 남기고 같은 실행 줄을 SUCCEEDED 로 갱신하며 RUN_COMPLETED 를 남긴다
        opt Memory 제안 설정이 켜짐
            C->>H: 방금 대화에서 남길 개인 사실 제안 요청
            alt 제안 실행 성공
                C->>C: 제안을 PROPOSED 로 저장한다
            else 제안 실행 실패
                C->>C: 제안 실행만 FAILED 로 남기고 원래 대화 성공은 유지한다
            end
        end
        alt 한 번에 받는 경로
            C-->>B: 답과 대화 식별자
        else 스트리밍 경로
            C-->>W: done 과 저장된 메시지 번호
            W-->>B: done
            B->>W: 저장된 대화 이력 조회
        end
    else 제출이나 실행 상태 조회 실패
        C->>C: 같은 실행 줄을 FAILED 로 갱신하고 오류 코드를 적으며 RUN_FAILED 를 남긴다
        C-->>W: 오류 응답이나 error 사건
    end
```

대화가 없으면 새로 만들고 첫 메시지가 에이전트를 정한다.
이어지는 요청이 에이전트를 다시 주더라도 대화에 적힌 값을 쓴다.
`hermes_session_id` 가 특정 profile 안의 session 이라, 중간에 에이전트가 바뀌면 그 session 이 가리키는 것이 없어진다.

실행 줄은 Hermes 를 부르기 전에 `RUNNING` 으로 먼저 만들어진다.
그래서 오래 도는 실행도 사용량 화면에서 보이고, 서버가 중간에 죽어도 그 실행이 기록에 남는다.

두 경로 모두 Hermes 실행 상태 조회가 돌려준 최종 `output` 과 `usage` 를 저장한다.
스트리밍 경로의 답 조각은 화면에만 쓰며, 이벤트 연결이 중간에 끝나도 최종 상태를 조회해 메시지와 실행 기록을 남긴다.
브라우저는 `done` 을 받으면 대화 이력을 다시 읽고 화면의 답 조각을 저장된 답으로 바꾼다.

**사건이 없는 동안에도 스트림에 바이트를 흘린다.**
모델이 도구 없이 생각만 하는 동안에는 보낼 사건이 없다.
실제 실행에서 사건 사이가 229초 벌어졌고, 앞단 프록시가 약 100초 만에 연결을 끊어 화면이 끊김을 알렸다.
Control Plane 은 실행이 도는 동안 `assistant.chat.stream-heartbeat`(기본 20초)마다 SSE 주석 줄 `:ping` 을 보낸다.
주석 줄은 사건이 아니라 web 의 파서가 건너뛰므로 화면에는 아무것도 바뀌지 않는다.

**실행의 시작과 끝은 두 경로 모두 남는다.**
`RUN_STARTED` 와 `RUN_COMPLETED` 와 `RUN_FAILED` 는 Hermes 사건을 옮겨 적은 것이 아니라
Control Plane 이 직접 적는 것이다.
그래서 사건 스트림을 읽지 못한 실행에도 실행의 시작과 끝이 남는다.
도구와 하위 에이전트 사건은 두 경로 모두 사건 스트림에서 옮겨 적는다. 한 번에 받는 경로는 화면으로 흘리지 않고 실행 기록에만 쌓으며, 답 조각 시각(`first_delta_at`)은 적지 않는다([ADR-090](../backend/docs/adr/ADR-090-한-번에-받는-경로도-hermes-사건-스트림을-열어-도구-사건을-남긴다.md)).

**사건 저장이 실패해도 대화는 성공으로 끝난다.**
사건은 관측용이고 그것 때문에 답이 사라지면 안 된다.
기동할 때 남은 실행을 정리하는 경로도 끝 사건을 남긴다. 실패로 적은 실행에는 그 오류 코드를 담은 `RUN_FAILED` 가 남는다.

## 먼저 살펴보기

사용자가 에이전트 상세나 점검 대화에서 「지금 살펴보기」 를 누르면 Control Plane 이 그 사용자 대신 점검 대화에 turn 하나를 연다.
단추를 누른 요청은 시작을 확인하고 곧바로 끝나고, 진행과 결과는 점검 대화의 대화 단위 SSE 와 이력으로 온다.

```mermaid
flowchart TD
    A[지금 살펴보기] --> B{시작 전 점검}
    B -- 막는 까닭 --> X[409 PROACTIVE_CHECK_UNAVAILABLE. 화면이 까닭과 끌 toolset 을 보인다]
    B -- 통과 --> C{점검 대화가 있는가}
    C -- 없다 --> D[점검 대화를 만든다]
    C -- 있다 --> E{turn 자리와 대화 잠금}
    D --> E
    E -- USER_BUSY 나 CONVERSATION_BUSY --> Y[409. 새로 만든 대화는 지운다]
    E -- 얻었다 --> F[202 conversationId. 화면이 점검 대화로 간다]
    F --> G[살펴보기 turn. 읽기 경계와 상한 안에서 돈다]
    G --> H{결과 블록}
    H -- 발견 --> I[검사한 결과를 답으로 남긴다]
    I --> P[문제 후보를 검사해 남긴다. 대화에 그리지 않는다. 후보 0개도 정상]
    H -- NOTHING_NEW --> J[새로 알릴 것이 없다는 알림 줄]
    H -- 없거나 읽지 못함 --> K[정리하지 못했다는 알림 줄]
    G -- 상한이나 중지 --> L[멈췄다는 알림 줄]
```

흐름의 세부와 경계는 [`backend/docs/flow.md`](../backend/docs/flow.md) 가 갖는다.

### 발견에 반응할 때

점검 대화의 답 아래 「새로 알릴 것」 발견마다 「받아들임」, 「나중에」, 「관심 없음」 이 있다.
반응은 그 발견의 마지막 단추이고, 다음 살펴보기가 입력과 되풀이 판정에서 읽는다.

```mermaid
sequenceDiagram
    participant B as 브라우저
    participant C as Control Plane
    participant H as Hermes

    B->>C: GET 점검 대화의 check-findings
    C-->>B: 발견, 지금 반응, dismissWindowDays
    B->>C: PUT check-findings/{번호}/reaction
    alt 남의 발견이거나 참고로 내린 발견
        C-->>B: 404 PROACTIVE_CHECK_NOT_FOUND
    else 지금 반응과 같다
        C-->>B: 204. 사건을 더 남기지 않는다
    else
        C->>C: decision_feedback_event 에 check_finding 사건을 덧붙인다
        C-->>B: 204. 화면이 목록을 다시 읽는다
    end
    Note over C,H: 다음 살펴보기
    C->>H: 최근에 알린 발견과 그 반응을 입력에 싣는다
    H-->>C: 결과 블록
    C->>C: 「관심 없음」 주제 키는 digest-window 동안 REPEATED 로 내린다
```

## 커넥터를 붙일 때

커넥터는 에이전트에게 쥐어 주는 도구 묶음이다. 계정은 「연결」 화면에서 한 번 연결하고, 그 연결을 에이전트 상세에서 붙인다.
순서와 실패 처리는 [`backend/docs/flow.md`](../backend/docs/flow.md) 의 「설치와 실패 처리」, 판정은 [`backend/docs/flow.md`](../backend/docs/flow.md) 의 「도구 호출 판정」 이 갖는다.

```mermaid
sequenceDiagram
    participant B as 브라우저
    participant C as Control Plane
    participant D as 대시보드 plugin
    participant A as 관리자
    participant G as 공유 gateway
    participant H as fos-ctx hook

    B->>C: POST /api/v1/connections/{id} (칸 값)
    C->>D: call verify.tool (트랜잭션 밖)
    D-->>C: 통과
    C->>C: 사용자 행 잠금
    C->>D: PUT /api/connector-vault
    C->>C: 연결 READY, 비밀 앞부분만 저장

    B->>C: PUT /api/v1/agents/{code}/connections/{connectorId}
    C->>C: 사용자 행, 에이전트 행 차례로 잠금. 주인, PRIVATE, 연결 READY 확인
    alt 비공개가 아니다
        C-->>B: 409 AGENT_CONNECTIONS_REQUIRE_PRIVATE
    else 연결이 READY 가 아니거나 보관 파일에 값이 없다
        C-->>B: 409 CONNECTOR_NOT_CONNECTED
    else single_binding 커넥터이고 그 연결이 다른 에이전트에 붙어 있다
        C-->>B: 409 CONNECTOR_SINGLE_BINDING
    else 붙일 수 있다
        C->>D: PUT /api/connectors (bind: vault)
        alt 대시보드 409 sandbox_unavailable. 실행 공간이 필요한데 정책에 없는 profile
            D-->>C: 409 (code: sandbox_unavailable)
            C-->>B: 409 AGENT_SANDBOX_UNAVAILABLE. 바인딩 행이 남지 않는다
        else 대시보드 409. 운영자 설정이나 다른 커넥터와 충돌
            D-->>C: 409
            C-->>B: 409 CONNECTOR_BIND_CONFLICT. 바인딩 행이 남지 않는다
        else 대시보드 401. 표식 없는 profile
            D-->>C: 401
            C-->>B: 409 CONNECTOR_PROFILE_NOT_READY. 바인딩 행이 남지 않는다
        else 뗀 기록에 없는 새 서버 이름을 더했다
            D-->>C: reload_pending
            C->>C: apply_due_at 에 지금 더하기 150초를 적는다
            C-->>B: 바인딩 PENDING. 화면은 「반영 대기」
        else 이미 있던 서버가 바뀌었거나 뗀 기록의 이름을 다시 붙였다
            D-->>C: restart_required
            C-->>B: 바인딩 PENDING, 재시작 대기
        end
    end

    Note over G: MCP 설정 맞추기가 60초마다 새 서버 이름을 연결한다
    C->>C: 30초마다 apply_due_at 이 지난 바인딩을 찾는다
    C->>C: 사용자 행, 에이전트 행 차례로 잠그고 apply_due_at 을 비운다
    C->>D: 설치를 다시 보내고 상태와 policy_hook 을 읽고 MCP probe
    alt probe 가 도구를 냈다
        C->>C: 바인딩 READY
    else probe 실패
        C->>C: 바인딩 PENDING. 다음 연결 확인이 다시 맞춘다
    end

    opt restart_required 였던 바인딩
        A->>G: 공유 gateway 재시작
        A->>C: POST /api/v1/admin/agents/{code}/connections/{connectorId}/confirm (restartRequiredSince)
        alt 재시작 뒤에 다시 설치됐다
            C-->>A: 409 CONNECTOR_RESTART_AGAIN
        else
            C->>D: 설치를 다시 보내고 상태와 policy_hook 을 읽고 MCP probe
            C-->>A: 바인딩 READY
        end
    end

    Note over G,H: 대화 turn 안에서 모델이 커넥터 도구를 부른다
    H->>C: POST /internal/hermes/connector-policy
    C->>C: 실행의 에이전트에 붙은 바인딩에서 서버 이름으로 연결을 고르고 판정
    C-->>H: allow, 거절, 승인 필요
    H-->>G: 허용이면 통과하고 결과를 external-data 로 감싼다

    B->>C: DELETE /api/v1/agents/{code}/connections/{connectorId}
    C->>C: 그 에이전트가 판정한 PENDING 승인 줄을 끝낸다
    C->>D: PUT /api/connectors (enabled: false)
    C-->>B: 204. 다음 실행부터 그 도구가 막힌다
```

- 같은 사용자의 등록, 확인, 해제, 붙이기, 떼기, 승인은 사용자 행 잠금으로 줄을 선다. 붙이기, 떼기, 반영 완료, 반영 예정 확인, 지우기는 그다음 에이전트 행을 잠근다. 공개 범위 변경도 같은 에이전트 행을 기다려 잠그므로 붙이기와 동시에 와도 한쪽이 다른 쪽의 커밋을 보고 판정한다
- 대시보드의 그 밖의 실패는 바인딩을 `PENDING` 으로 남기고 502 `CONNECTOR_OPERATION_FAILED` 다. 다음 연결 확인이 설치를 다시 보낸다
- 뗀 기록에 없는 새 이름을 더한 붙이기는 공유 gateway 를 재시작하지 않는다. Control Plane 이 반영 예정 시각이 지나면 스스로 확인한다([ADR-20261007 / connector-live-reload](adr/ADR-20261007-connector-live-reload.md)). 관리자 반영 완료는 `restart_required` 인 바인딩에만 남는다
- 바인딩이 `READY` 가 되기 전이나 연결이 확인되지 않은 동안의 호출은 판정이 `NOT_READY` 로 막는다. 그 에이전트의 다른 도구는 그대로 돈다

## 실행이 실패할 때

| 오류 코드 | 원인 | 화면이 하는 일 |
| --- | --- | --- |
| `HERMES_PROFILE_KEY_MISSING` | profile 의 key 가 준비되지 않았다 | 서버 설정 문제로 안내한다 |
| `HERMES_RUN_TIMEOUT` | 제한 시간 안에 끝나지 않았다 | 다시 보내도록 안내한다 |
| `HERMES_BUSY` | Hermes 가 동시 실행 한도에 닿아 429 로 거절했다 | 붐빈다고 알리고 잠시 뒤에 다시 보내도록 안내한다 |
| `HERMES_UNAVAILABLE` | Hermes 에 닿지 못했다 | 연결 실패로 안내한다 |
| `PROVIDER_BLOCKED` | 고른 모델의 provider 계정이 모두 막혔다. Hermes 가 계정을 돌려 쓰고도 실패한 것이다 | 다른 모델을 골라 다시 보내도록 안내한다. 쓴 문장은 입력창에 되돌린다 |
| `MODEL_HIDDEN` | 이 실행이 돌 모델을 그룹이 숨겼다. 대화가 고른 모델, 단계의 모델, 에이전트 기본 모델, profile 의 기본 모델이 모두 대상이다 | 입력창의 설정에서 다른 모델을 고르도록 안내한다 |
| `EXECUTION_NOT_RUNNING` | 중지하려는 실행이 이미 끝났다 | 알리지 않고 곧 올 끝 사건을 기다린다 |
| `MESSAGE_NOT_LATEST` | 다시 생성하려는 답이 마지막이 아니다 | 이력을 다시 읽는다 |
| `CONVERSATION_BUSY` | 그 대화에서 도는 turn 이 있다. 보내기와 다시 생성과 결과 다시 전달이 받는다 | 글만 보낸 것이면 대기 메시지로 다시 넣는다. 사진이 붙었거나 다시 생성이거나 흐름이 붙은 에이전트면 끝난 뒤에 다시 보내게 하고 쓴 문장을 입력창에 되돌린다 |
| `USER_BUSY` | 그 사용자가 다른 대화와 위임으로 동시 실행 한도를 모두 쓰고 있다([`backend/docs/flow.md`](../backend/docs/flow.md)). 보내기, 다시 생성, 결과 다시 전달, 대기 메시지 turn, 흐름 단계가 받는다 | 진행 중인 작업이 끝난 뒤 다시 보내도록 안내한다. 쓴 문장은 입력창에 되돌리고 대기 메시지로 넣지 않는다. 대기 메시지 turn 이면 대기 줄이 멈춘 채 남는다 |
| `PENDING_QUEUE_FULL` | 대기 메시지가 5개이거나, 더하면 합친 길이가 8000자를 넘는다 | 답이 끝난 뒤 보내도록 안내한다. 쓴 문장은 입력창에 되돌린다 |
| `PENDING_MESSAGE_NOT_FOUND` | 취소하려는 대기 메시지가 이미 보내졌거나 없다 | 입력창에 되돌리지 않고 대기 줄을 다시 읽는다 |
| `EXECUTION_NOT_FOUND` | 없는 실행이거나 남의 실행이다 | 사용량 목록으로 되돌린다 |
| `AUTONOMY_DECISION_NOT_FOUND` | 반응할 판정이 없다. 남의 것이거나 매일 루프가 보인 판정이 아니다([`backend/docs/flow.md`](../backend/docs/flow.md) 의 「사용자에게 보이는 것」) | 서버 문구를 항목 아래에 보이고 지금 화면을 다시 읽는다 |
| `PROACTIVE_LOOP_UNAVAILABLE` | 설치가 매일 루프를 열지 않아 켤 수 없다([`backend/docs/flow.md`](../backend/docs/flow.md) 의 「사용자 설정」) | 켜기를 막고 설치에서 꺼져 있다고 보인다. 끄기와 쉬기는 된다 |
| `PROACTIVE_CHECK_UNAVAILABLE` | 먼저 살펴보기를 시작할 수 없다. 까닭은 상태 조회가 준다([`backend/docs/flow.md`](../backend/docs/flow.md) 의 「시작 전 점검」) | 상태를 다시 읽어 까닭마다 할 일을 보인다. toolset 이 걸렸으면 끌 toolset 이름을 보인다 |
| `DELIVERY_NOT_FOUND` | 다시 전달하려는 결과 묶음이 그 대화에 없다 | 안내를 보이고 이력을 다시 읽는다 |
| `DELIVERY_NOT_RETRYABLE` | 다시 전달하려는 묶음이 이미 전하는 중이거나 끝났다. 묶음의 결과가 남지 않았거나 대화에 흐름이 붙은 때도 같다 | 안내를 보이고 이력을 다시 읽는다 |

사용자가 보낸 요청이 `HERMES_BUSY` 나 `USER_BUSY` 를 받으면 Control Plane 은 다시 보내지 않는다. 위임 결과 자동 turn 의 재시도는 [`backend/docs/flow.md`](../backend/docs/flow.md) 의 「한도에 닿을 때」 가 갖는다.
한도에 닿은 상태에서 다시 보내면 한도를 더 밀어붙인다. 다시 보낼지는 사람이 정한다.

`EXECUTION_NOT_FOUND` 는 두 원인을 같은 응답으로 숨긴다.
남의 실행이 있는지조차 알려주지 않기 위해서다.

실패한 실행도 기록에 남는다.
사용량 화면에서 무엇이 실패했는지 볼 수 있다.
실행 트리의 실패 줄은 오류 코드를 위 표의 안내 문구로 바꿔 보이고, 코드 원문은 관리자 영역의 실행 상세(`/admin/executions/{id}`)에서만 함께 보인다.

## 지금 화면을 열 때

판정 표는 [`backend/docs/flow.md`](../backend/docs/flow.md), 화면은 [`web/docs/prd.md`](../web/docs/prd.md) 가 갖는다.

```mermaid
sequenceDiagram
    participant B as 브라우저
    participant W as Next.js 서버 라우트
    participant C as Control Plane

    B->>W: / 를 연다
    W-->>B: 새 대화 화면. 서버에서 읽는 것이 없다
    B->>W: 그린 뒤 건수를 묻는다
    W->>C: GET /api/v1/attention/summary
    alt 읽었다
        C-->>B: nowCount
        B->>B: 0 보다 크면 「확인할 것 N건」 을 그린다
    else 실패했다
        B->>B: 그 줄을 그리지 않는다
    end
    B->>W: /now 를 연다
    W->>C: GET /api/v1/attention
    C->>C: 실행, 승인 줄, Memory 제안, 할 일, 대화를 요청자 것만 읽는다
    C->>C: 숨기기와 미루기를 읽고 후보마다 NOW, LATER, SUPPRESSED 를 정한다
    C->>C: 보인 항목마다 SHOWN 사건을 한 번 남긴다
    C-->>W: 카드 다섯, 항목의 이유와 출처
    W-->>B: 지금 화면
    opt 숨기기나 미루기
        B->>W: hide 나 snooze
        W->>C: POST /api/v1/attention/hide 나 /snooze
        C->>C: attention_control 을 쓴다. 원래 기록은 그대로다
    end
    opt 항목의 동작
        B->>W: 승인, 할 일 받아들이기 같은 기존 경로
        B->>W: OPENED 나 ACTED 사건
    end
```

source 하나를 읽지 못하면 그 카드만 「불러오지 못했다」 로 내고 나머지 카드는 그린다.
판정은 Hermes 를 부르지 않고, 실행이나 커넥터 호출을 시작하지 않는다.

## 기억을 남길 때

계약은 [`backend/docs/flow.md`](../backend/docs/flow.md) 의 「에이전트가 기억을 남기는 길」 이 갖는다.

```mermaid
sequenceDiagram
    participant B as 브라우저
    participant C as Control Plane
    participant H as Hermes

    B->>C: 질문을 보낸다
    C->>C: 질문 메시지를 저장하고 실행 줄에 잇는다(execution_question)
    C->>H: POST /v1/runs (지시문에 기억 지침)
    H->>C: POST /mcp memory_remember 와 서명한 _fos_ctx
    C->>C: 루트 실행인가, 부정이 질문과 본문 한쪽에만 있지 않은가, 바깥 도구와 질문 없는 실행이 없는가, 민감하지 않고 민감해 보이는 글이 없는가
    alt 모두 만족
        C->>C: memory 를 ACCEPTED 로, memory_capture 를 CREATED 로
        C-->>H: 기억했다
    else 하나라도 어긋남
        C->>C: memory 를 PROPOSED 로, memory_capture 를 PROPOSED 로
        C-->>H: 제안으로 남겼다
    end
    H-->>C: 답이 끝난다
    B->>C: 대화의 기억 기록을 읽는다
    C-->>B: 답 아래 「기억했어요」 나 제안 카드
    Note over C: 다음 turn 부터 짧은 개인 항목은 개인 사실 구역에 본문까지 실린다
    B->>C: 대화의 참고한 기억을 읽는다(memory-uses)
    C->>C: 답 실행마다 본문을 실은 항목과 memory_read 로 읽은 항목을 모으고 지금 볼 수 있는 것만 남긴다
    C-->>B: 답 아래 「참고한 기억 N개」
```

## 할 일을 제안할 때

계약은 [`backend/docs/flow.md`](../backend/docs/flow.md) 가 갖는다.

```mermaid
sequenceDiagram
    participant H as Hermes
    participant C as Control Plane
    participant B as 브라우저

    H->>C: POST /mcp follow_up_propose 와 서명한 _fos_ctx
    C->>C: origin 실행에서 주인과 대화를 정한다
    alt 같은 할 일이 열려 있다
        C-->>H: 새로 만들지 않았다
    else 이 대화에서 거절한 적이 있거나 열린 제안이 많다
        C-->>H: isError 와 그 까닭
    else
        C->>C: follow_up 을 PROPOSED 로 만든다
        C-->>H: 제안했다
    end
    B->>C: 지금 화면의 「받아들이기」
    C->>C: OPEN 으로 바꾸고 accepted_at 을 적는다
```

받아들이기 전의 제안은 지금 화면에 보이지만 건수에 세지 않는다.

## 커넥터 READ 데이터의 흐름

연결을 붙인 에이전트가 커넥터에서 읽은 글이 모델, 셸, 웹, 다른 커넥터, Memory, 결과물, 기록으로 가는 길을 갖는다.
길마다 지금 무엇이 막고 무엇이 막지 않는지, 제품 정책상 허용인지 승인인지 거절인지를 흐름 판정 코드와 함께 적는다.
원칙과 기본값을 지금 바꾸지 않은 까닭은 [ADR-20261008 / read-data-flow](adr/ADR-20261008-read-data-flow.md) 가 갖는다.

이 문서는 실제 유출 사고를 다루지 않는다. 지원하는 도구로 생길 수 있는 흐름의 범위를 정한다.
각 커넥터의 도구 선언은 [커넥터 도구 정책](../backend/docs/flow.md) 이, 실행 공간은 [ADR-086](adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) 과 [`hermes/docs/hermes-contract.md`](../hermes/docs/hermes-contract.md) 가 갖는다.

### 원칙

**외부 서비스를 읽을 권한과 읽은 글을 다시 보내고, 오래 남기고, 밖으로 전할 권한은 다르다.**
연결과 바인딩은 앞의 것만 준다. 뒤의 것은 그 글이 가는 곳(sink)마다 따로 정한다.

- 읽은 글은 늘 신뢰하지 않는 글이다. 안에 든 문장이 모델을 속여 다음 도구 호출을 고를 수 있다고 본다
- 출처 표시(`<external-data>`)와 지침은 모델이 그 글을 지시로 읽을 가능성을 줄일 뿐이다. 경계로 세지 않는다
- 경계는 Control Plane 이나 실행 공간이 호출마다 결정적으로 판정하는 곳에만 있다

### 신뢰 경계

```mermaid
flowchart LR
    subgraph EXT["외부 서비스"]
        SVC["메일, 블로그 등"]
    end
    subgraph HP["Hermes 프로세스"]
        MCP["커넥터 MCP 서버<br/>(profile .env 의 값)"]
        HOOK["fos-ctx hook<br/>판정 질의, external-data 감싸기"]
        LOOP["모델 루프"]
        WEB["web, browser 도구"]
    end
    subgraph SB["실행 공간 컨테이너 (등록한 profile)"]
        SH["terminal, file, execute_code<br/>/workspace"]
    end
    subgraph CP["Control Plane"]
        POL["커넥터 도구 판정"]
        CPM["MCP 도구<br/>memory, artifact, follow_up, agent_*"]
        DB[("대화, 실행 기록, 승인 줄")]
    end
    UI["사용자 화면<br/>승인 카드, 제안 카드"]
    PROV["모델 공급자"]
    NET["인터넷"]

    SVC -->|READ 결과| MCP --> HOOK --> LOOP
    LOOP <--> PROV
    LOOP -->|커넥터 호출| HOOK -->|판정 질의| POL
    POL -->|승인 필요| UI
    LOOP --> CPM --> DB
    LOOP --> SH -->|막지 않음| NET
    LOOP --> WEB -->|막지 않음| NET
    LOOP -->|최종 답| DB
```

| 경계 | 넘을 때 판정하는 곳 | 판정하지 않는 것 |
| --- | --- | --- |
| 외부 서비스에서 모델로 | `fos-ctx` 의 `transform_tool_result` 가 감싼다. 판정은 아니다 | 글의 내용 |
| 모델에서 커넥터 도구로 | `fos-ctx` 의 `pre_tool_call` 이 묻고 Control Plane 이 판정한다 | 인자의 내용과 그 글이 어디서 왔는지 |
| 모델에서 Control Plane 도구로 | Control Plane 이 도구마다 판정한다 | 도구마다 다르다. 아래 「흐름 판정 표」 를 본다 |
| 모델에서 셸, 웹, 브라우저로 | 없다. 도구를 켤 수 있는 등급만 있다 | 명령, 주소, 보낼 글 |
| 셸에서 비밀값으로 | 실행 공간을 적용한 profile 에서는 비밀이 컨테이너에 없다 | 실행 공간을 적용하지 않은 profile |
| 실행 공간에서 인터넷으로 | 없다. 운영이 연결을 기록한다 | 보내는 내용 |

### 보호 수단이 보장하는 범위

| 수단 | 보장하는 것 | 보장하지 않는 것 |
| --- | --- | --- |
| `<external-data>` 감싸기 | 바인딩 profile 의 커넥터 도구 결과에 출처 안내와 닫는 표시 바꾸기를 붙인다. Hermes 의 `<untrusted_tool_result>` 가 그 바깥에 한 번 더 있다 | 모델이 그 글을 지시로 따르지 않는 것. hook 이 실패하면 감싸기 없이 원래 결과가 간다. 셸, 웹, 파일 도구의 결과와 커넥터 출력 파일은 감싸지 않는다 |
| 커넥터 도구 판정 | 바인딩한 커넥터 서버의 도구만 지나간다. `schema: 2` 의 선언 없는 도구, `DESTRUCTIVE`, `FINANCIAL` 은 거절한다. `schema: 1` 의 선언 없는 도구는 쓰기로 읽는다. 쓰기는 승인이나 상시 허락이 있어야 나간다. Control Plane 이 답하지 않거나 틀린 답을 주면 막는다 | 인자에 무엇이 실렸는지, 그 글이 다른 커넥터에서 왔는지. `fos-ctx` 가 꺼지거나 옛 판이면 판정 없이 나간다. 그때 연결 확인이 바인딩을 `PENDING` 으로 둔다 |
| 승인 카드 | 사람이 그 호출의 인자를 보고 정한다. 상시 허락을 닫은 도구는 가려지는 글이 있으면 승인하지 못한다 | 상시 허락이 있는 기간의 호출. 사람이 본문을 읽지 않고 누르는 것 |
| 실행 공간 | 등록한 profile 의 셸, 파일 도구, `execute_code` 가 profile `.env`, 연결 보관 파일, 대응 파일, 다른 사용자의 파일에 닿지 않는다 | 밖으로 나가는 요청. 실행 공간을 적용하지 않은 profile. 컨테이너 밖에서 도는 web, browser 도구 |
| 바인딩의 주인과 공개 범위 | 연결이 붙은 에이전트는 `PRIVATE` 이고 주인이 자기 연결만 붙인다. 남이 주인의 계정으로 외부 서비스를 부르지 못한다 | 주인 자신의 실행 안에서 글이 어디로 가는지 |
| `memory_remember` 의 바깥 도구 확인 | 그 대화의 도구가 모두 안쪽 목록(`McpMemoryRemember.INTERNAL_TOOLS`)에 들 때만 바로 저장할 수 있다. 커넥터 도구, 웹, 하위 에이전트, 맡긴 실행의 답을 돌려주는 `agent_status` 가 하나라도 있으면 제안으로 둔다. 나머지 조건은 [`backend/docs/flow.md`](../backend/docs/flow.md) 의 「바로 저장 판정」 이 갖는다. | 사건 저장이 실패해 도구 시작 줄이 빠진 대화 |
| 도구 내용 가림 | 커넥터 도구의 입력과 결과는 실행 기록에 `[연결 도구 내용 가림]`만 남는다. 실행 트리에서 커넥터 호출 뒤에는 일반 도구 내용과 하위 에이전트 목표도 길이만 남긴다. 호출 전에는 비밀 모양을 가리고 500자로 자른다 | 이미 저장된 사건. 이전 turn에서 읽은 본문. Hermes가 보내기 전에 자른 원문 길이 |

### 흐름 판정 코드

코드는 이 문서의 표에서만 쓰는 이름이다. API 응답이나 DB 값이 아니다.
`RISK_NOT_OPEN` 과 `READ_ONLY_RUN` 은 커넥터 판정의 `deny_reason` 과 같은 뜻이다.

| 코드 | 판정 | 뜻 |
| --- | --- | --- |
| `WRAPPED_CONTEXT` | 허용 | 모델 문맥에 출처 표시와 함께 들어간다 |
| `SAME_OWNER_SINK` | 허용 | 그 사용자만 보는 곳에 남는다. 서버 관리자는 DB 에서 볼 수 있다 |
| `REDACTED_RECORD` | 허용 | 기록에는 가린 값만 남는다 |
| `STANDING_GRANT` | 허용 | 사용자가 그 도구에 미리 준 상시 허락으로 나간다. 글의 출처는 보지 않는다 |
| `PER_CALL_APPROVAL` | 사용자 승인 | 호출마다 사람이 인자를 보고 승인해야 나간다 |
| `PROPOSAL_ONLY` | 사용자 승인 | 제안으로만 남고 사람이 받아들여야 효력이 생긴다 |
| `RISK_NOT_OPEN` | 거절 | 위험도가 `DESTRUCTIVE` 나 `FINANCIAL` 이라 늘 거절한다 |
| `READ_ONLY_RUN` | 거절 | 사람이 보지 않는 먼저 살펴보기 트리라 승인 없는 READ 만 받는다 |
| `NO_SESSION` | 거절 | 실행 맥락이 없는 커넥터 호출이다. `execute_code` 안의 호출이 여기 온다 |
| `CREDENTIAL_ABSENT` | 거절 | 실행 공간에 비밀값이 없어 닿을 것이 없다 |
| `HIDDEN_ARGS` | 거절 | 상시 허락을 닫은 도구의 인자에 가려지는 글이 있어 승인해도 실행하지 않는다 |
| `UNMEDIATED_EGRESS` | 통제 없음 | 호출마다 판정하는 곳이 없다. 도구를 켜는 등급과 사후 기록만 있다. 결정으로 감수한다 |

### 흐름 판정 표

출처는 커넥터 READ 도구의 결과다. 예시는 Gmail 메일 본문이지만 가계부나 건강 기록처럼 민감한 READ 도 같다.
판정은 출처를 보지 않으므로 어느 커넥터에서 읽었는지에 따라 달라지지 않는다.

| 번호 | 흐름 | 판정 | 코드 | 판정하는 곳 | 합성 시험 |
| --- | --- | --- | --- | --- | --- |
| RF-01 | READ 결과가 모델 문맥으로 | 허용 | `WRAPPED_CONTEXT` | `fos-ctx` `transform_tool_result` | `test_read_data_flow` `test_read_result_reaches_model_inside_external_data`, `test_fos_ctx` `TransformToolResultTest` |
| RF-02 | 최종 답과 대화 기록으로 | 허용 | `SAME_OWNER_SINK` | 대화 주인 확인 | 별도 시험 없음. 대화 읽기 권한 시험이 본다 |
| RF-03 | 같은 커넥터의 밖으로 나가는 쓰기로 (메일 보내기, 답장) | 사용자 승인 | `PER_CALL_APPROVAL` | Control Plane 판정. `outbound` 와 `"grant": false` | `ToolPolicyDecisionTest` 의 상시 허락을 닫은 도구 시험, `test_read_data_flow` `test_writes_carrying_read_body_are_left_to_control_plane` |
| RF-04 | 다른 커넥터의 쓰기로, 상시 허락이 있을 때 (블로그 임시저장, 메일 초안) | 허용 | `STANDING_GRANT` | Control Plane 판정 | `ToolPolicyDecisionTest` 의 상시 허락 허용 시험, `test_read_data_flow` `test_writes_carrying_read_body_are_left_to_control_plane` |
| RF-05 | 다른 커넥터의 쓰기로, 상시 허락이 없거나 닫혔을 때 | 사용자 승인 | `PER_CALL_APPROVAL` | Control Plane 판정 | `ToolPolicyDecisionTest` |
| RF-06 | `DESTRUCTIVE`, `FINANCIAL` 도구로 | 거절 | `RISK_NOT_OPEN` | Control Plane 판정. 설치가 모델에게서 뺀다 | `ToolPolicyDecisionTest` |
| RF-07 | 먼저 살펴보기 트리 안에서 쓰기로 | 거절. 쓰기를 허용한 살펴보기는 사용자 승인 | `READ_ONLY_RUN`, `PER_CALL_APPROVAL` | Control Plane 판정 | `ToolPolicyDecisionTest` 의 살펴보기 시험 |
| RF-08 | 셸과 `execute_code` 를 거쳐 인터넷으로 | 통제 없음 | `UNMEDIATED_EGRESS` | 없다. 셸 계열은 관리자 등급이다 | `test_read_data_flow` `test_shell_web_and_browser_calls_are_not_inspected` |
| RF-08a | `execute_code` 스크립트 안에서 커넥터 도구로 | 거절 | `NO_SESSION` | `fos-ctx` `pre_tool_call` | `test_read_data_flow` `test_connector_call_from_code_has_no_session_and_is_blocked` |
| RF-09 | `web` 도구의 검색어나 주소로 | 통제 없음 | `UNMEDIATED_EGRESS` | 없다. 연결이 붙은 에이전트에서도 `web`은 주인 등급으로 둔다. 숨은 지시로 본문이 검색어나 주소에 실려 나갈 수 있는 위험을 감당한다(2026-10-08 사용자 결정(#320)) | `test_read_data_flow` `test_shell_web_and_browser_calls_are_not_inspected` |
| RF-10 | 내장 `browser` 도구의 주소로 | 통제 없음 | `UNMEDIATED_EGRESS` | 없다. 관리자 등급이고 profile 틀은 꺼 둔다 | `test_read_data_flow` `test_shell_web_and_browser_calls_are_not_inspected` |
| RF-11 | 사용자 `/workspace` 의 파일로 | 허용 | `SAME_OWNER_SINK` | 실행 공간의 사용자 디렉터리 | 별도 시험 없음. 실행 공간 측정은 [`hermes/docs/hermes-contract.md`](../hermes/docs/hermes-contract.md) 가 갖는다 |
| RF-12 | `artifact_write` 결과물로 | 허용 | `SAME_OWNER_SINK` | Control Plane. 대화 주인만 읽고 스크립트와 외부 이미지를 막는 머리글을 붙인다. 결과물 안의 링크는 사용자가 누르면 새 창으로 열린다 | `ArtifactTest` 의 머리글 시험과 남의 대화 시험, `test_read_data_flow` `test_control_plane_sinks_keep_body_and_get_signed_context` |
| RF-13 | `memory_remember` 로 | 사용자 승인 | `PROPOSAL_ONLY` | Control Plane. 대화에 바깥 도구가 있으면 제안이다 | `McpMemoryRememberToolTest` 의 커넥터 READ 시험과 바깥 도구 시험 |
| RF-14 | `follow_up_propose` 로 | 사용자 승인 | `PROPOSAL_ONLY` | Control Plane. 사람이 받아들여야 할 일이 된다 | `McpFollowUpToolTest` |
| RF-15 | `agent_delegate` 의 `task` 로 다른 에이전트에 | 허용. 연결이 붙은 에이전트의 결과는 돌아올 때 `ExternalData` 로 감싼다(`McpToolService`, `ChatDeliveryInput`) | `SAME_OWNER_SINK` | Control Plane. 대상은 요청자 소유이거나 그룹 공개 에이전트다. 자식 실행은 요청자 명의이고 대상 에이전트의 도구로 돈다. 그 도구로 가는 흐름은 이 표의 다른 줄이 정한다. 결과는 다음 turn 전달과 `agent_status` 두 길로 돌아온다 | `test_read_data_flow` `test_control_plane_sinks_keep_body_and_get_signed_context` |
| RF-16 | 실행 기록의 도구 내용으로 | 허용(가림). 트리에서 커넥터 호출 뒤에는 일반 도구도 이름과 받은 내용의 길이만 남긴다(#319) | `REDACTED_RECORD` | `ToolDetailRedactor`, `HermesRunEventStream`. 커넥터 정책 기록으로 루트와 자식, 형제를 함께 확인한다. 이력 조회 실패도 가린다 | `ToolDetailRedactorTest`의 다른 도구 인자 시험, `ToolDetailEventStreamTest`의 호출 전후와 트리 이력 시험, `ConnectorCallHistoryTest` |
| RF-17 | 승인 줄로 | 허용 | `SAME_OWNER_SINK` | 승인 줄에 인자 원문이 16KB 까지, 결과 글이 남는다. 주인 화면은 가린 인자를 받는다 | `ConnectorActionServiceTest` |
| RF-18 | `fos-ctx` 와 backend 로그로 | 허용(가림) | `REDACTED_RECORD` | hook 은 인자와 결과 본문을 로그에 남기지 않는다. backend 는 도구 인자와 결과를 로그에 남기는 줄이 없다(코드 확인, 시험 없음). Hermes core 의 로그는 확인하지 않았다 | `test_fos_ctx` 의 `test_logs_hide_token_signature_and_args`, `test_unreadable_tool_map_returns_none_without_leaking` |
| RF-19 | 커넥터 출력 파일로 | 허용 | `SAME_OWNER_SINK` | 그 profile 의 실행 공간에만 읽기 전용으로 붙는다. 셸이 읽은 뒤는 RF-08 과 같다 | `test_dashboard_profile_api_connector_binding_output`, `test_dashboard_profile_api_sandbox_terminal` 의 출력 디렉터리 읽기 전용 시험 |
| RF-20 | 모델 공급자로 | 허용 | `WRAPPED_CONTEXT` | 없다. 대화에 쓰인 글은 요청의 일부다([`privacy.md`](privacy.md)) | 해당 없음 |
| RF-21 | 그 밖의 주인 등급 도구로 (`vision`, `tts`) | 통제 없음 | `UNMEDIATED_EGRESS` | 없다. `vision_analyze`는 모델이 준 HTTP(S) 주소를 Hermes에서 내려받는다. 주소 안전성과 사이트 정책 검사는 READ 본문 반출을 판정하지 않으므로 RF-09와 같은 길이다 | Hermes v0.21.5 소스 확인(2026-10-08). 아래 근거를 본다 |
| RF-22 | 그 밖의 관리자 등급 도구로 (`image_gen`, `video_gen`, `discord`, `homeassistant`, `spotify`, `computer_use`, `cronjob`, `session_search`) | 통제 없음 | `UNMEDIATED_EGRESS` | 없다. 관리자가 켠다. `cronjob` 은 글을 Hermes 예약 작업에 오래 남기고 그 작업은 local 로 돈다(RC-02) | 해당 없음 |

RF-21은 Hermes v0.21.5(태그 `v2026.9.24`)의 소스로 확인했다.
[`vision_tools.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/vision_tools.py)의 `_handle_vision_analyze`는 `image_url`을 `_prepare_image`로 넘긴다.
[`image_source.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/image_source.py)의 `resolve_image_source`는 HTTP(S) 입력을 `_download_to_bytes`로 내려받는다.
그 함수가 부르는 `_download_image`와 `_download_media`는 주소 검사 뒤 HTTP GET을 보낸다.
이 확인은 도구의 등급이나 승인 계약을 바꾸지 않는다.

비밀값(OAuth 토큰, MCP 토큰)의 흐름이다.

| 번호 | 흐름 | 판정 | 코드 | 판정하는 곳 | 합성 시험 |
| --- | --- | --- | --- | --- | --- |
| RC-01 | 실행 공간을 적용한 profile 의 셸이 `.env` 나 보관 파일로 | 거절 | `CREDENTIAL_ABSENT` | 실행 공간에 그 경로가 없다 | 운영과 같은 이미지의 측정. [`hermes/docs/hermes-contract.md`](../hermes/docs/hermes-contract.md) 의 「측정 결과」 |
| RC-02 | 실행 공간을 적용하지 않은 profile 의 셸이 `.env` 나 보관 파일로 | 통제 없음 | `UNMEDIATED_EGRESS` | 없다. ADR-083 이 감수한다 | 해당 없음 |
| RC-03 | 스킬 앞머리가 값을 실행 공간에 넣게 하는 길 | 거절 | `CREDENTIAL_ABSENT` | Control Plane 의 스킬 저장과 도구 저장(`AGENT_SKILL_REQUESTS_SECRETS`), 대시보드 plugin 의 manifest 검증 | `SkillFrontmatterTest`, `AgentToolServiceTest`, `test_connectors_contract` |
| RC-04 | 실행 기록, 승인 카드, 응답으로 | 허용(가림) | `REDACTED_RECORD` | `ToolDetailRedactor`. 비밀 모양과 비밀 키 이름의 값을 가린다 | `ToolDetailRedactorTest` |
| RC-05 | 상시 허락을 닫은 쓰기의 인자로 | 거절 | `HIDDEN_ARGS` | 가려지는 글이 있는 승인 줄은 승인하지 못한다 | `ConnectorActionServiceTest` |

### 열린 틈

정한 계약과 달라 고칠 것이다. 한 실행 안의 흐름이 아니라 이전 turn 의 본문에 걸린 틈이라 판정 표의 줄로 두지 않는다.

| 틈 | 지금 | 고칠 방향 |
| --- | --- | --- |
| 이전 turn의 본문 | 커넥터 호출 뒤 일반 도구와 하위 에이전트 목표는 가리지만, 새 실행 트리는 이전 turn에서 읽은 본문까지 추적하지 않는다. 이미 저장된 사건도 다시 가리지 않는다 | 대화 이력을 포함한 가림 범위는 별도 결정이 필요하다 |

### 실행 공간이 해결한 것과 남은 것

| 해결한 것 | 남은 것 |
| --- | --- |
| 등록한 profile 의 셸, 파일 도구, `execute_code` 가 profile `.env`, 연결 보관 파일, 대응 파일, `fos-ctx` 설정에 닿지 않는다. 셸로 승인을 비켜 가는 세 길(토큰 직접 사용, 대응 파일 고치기, hook 끄기)이 막힌다 | 밖으로 나가는 요청을 막지 않는다. 승인 없이 부른 READ 결과를 셸이 인터넷으로 보낼 수 있다(RF-08) |
| 다른 사용자의 `/workspace` 와 첨부가 보이지 않는다 | 같은 사용자의 에이전트끼리는 `/workspace` 를 함께 쓴다. 웹 도구가 없는 에이전트가 쓴 파일을 셸이 있는 다른 에이전트가 읽어 보낼 수 있다 |
| `execute_code` 안에서 커넥터 도구를 부르지 못한다(RF-08a) | web 과 browser 도구는 컨테이너 밖 Hermes 프로세스에서 돈다. 실행 공간과 상관없이 RF-09, RF-10 이 남는다 |
| 스킬 앞머리로 값을 넣는 길을 저장 때 막는다(RC-03) | 정책에 등록하지 않은 profile 과 기존 Hermes 예약 작업은 local 로 돈다. 그곳에서는 RC-02 가 남는다 |
| | 운영자가 읽기 전용으로 붙인 token 파일은 그 실행 공간에서 셸이 읽는다 |

### 운영 기본값을 검토한 결과

민감한 연결이 붙은 에이전트에서 셸이나 임의 네트워크를 기본으로 막는 안을 검토했다.
**지금은 기본값을 바꾸지 않는다.** 까닭과 버린 대안은 [ADR-20261008 / read-data-flow](adr/ADR-20261008-read-data-flow.md) 가 갖는다.

`web`의 주인 등급 유지는 2026-10-08 사용자 결정(#320)으로 확정했다.
첫 로그인에 만든 기본 에이전트는 실행 공간이 있으면 셸, 파일, `execute_code` 를 켜고 시작한다([ADR-20261008 / default-toolsets](adr/ADR-20261008-default-toolsets.md)). 그 에이전트에 연결을 붙이면 RF-08 이 관리자가 고르지 않아도 열린다. 실행 공간이 없으면 셸 계열을 빼므로 RC-02 는 기본값으로 열리지 않는다.
RF-09의 반출 위험을 감당하고, RF-21의 `vision_analyze`도 주소를 내려받는 길로 확인했다.
실행 공간의 밖으로 나가는 요청을 허용 목록으로 거르는 것은 ADR-086이 실측 비용을 본 뒤 다시 정하기로 했다.

사용자가 명시적으로 승인한 여러 출처의 작업은 막지 않는다. 메일을 읽고 그 내용으로 자기 블로그 임시저장을 만드는 것이 그 예다(RF-04).

### 시험을 돌리는 법

합성 시험은 지어낸 메일 본문과 `example` 주소만 쓴다.
표에서 시험이 「해당 없음」 이거나 「별도 시험 없음」 인 줄은 판정하는 곳이 이 저장소의 코드 밖에 있거나 운영 측정으로 확인한 줄이다.

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests -p 'test_read_data_flow.py'
# cwd: backend/
./gradlew test --tests '*ToolDetailRedactorTest' --tests '*McpMemoryRememberToolTest' --tests '*ToolPolicyDecisionTest'
```

## 파일 공간을 열 때

경로 규칙과 API 는 [`backend/docs/code-architecture.md`](../backend/docs/code-architecture.md) 의 「실행 공간 파일」, 결정은 [ADR-20261009 / workspace-explorer](adr/ADR-20261009-workspace-explorer.md) 가 갖는다.

```mermaid
sequenceDiagram
    participant B as 브라우저
    participant W as Next.js 서버 라우트
    participant C as Control Plane
    participant D as 읽기 전용 마운트

    B->>W: /files 를 연다(페이지만 받는다. 서버에서 읽는 것은 없다)
    B->>W: GET /api/workspace
    W->>C: GET /api/v1/workspace
    alt 루트가 설정되지 않았거나 디렉터리가 아니다
        C-->>B: available false. 「파일 공간을 쓸 수 없어요」
    else 사용자 디렉터리가 없다
        C-->>B: exists false. 「아직 에이전트가 만든 파일이 없어요」
    else
        C-->>B: 상태와 함께 쓰는 에이전트
        B->>W: 목록 ?path=
        W->>C: GET /api/v1/workspace/entries?path=
        C->>D: u<번호> 부터 조각마다 링크를 따라가지 않고 연다
        alt 경로 규칙에 어긋난다
            C-->>B: 400. 목록 대신 오류 안내와 맨 위로 가기
        else 없거나 링크를 지난다
            C-->>B: 404. 「찾을 수 없어요」 와 맨 위로 가기
        else
            C-->>B: 1,000 줄까지와 truncated
        end
    end
    opt 파일을 고른다
        B->>B: 확장자와 크기로 미리보기 종류를 정한다
        alt 미리보기가 있다
            B->>W: GET /api/workspace/files/<경로>
            W->>C: 같은 경로
            C-->>B: 본문과 머리글. HTML 은 스크립트 없는 iframe
        else 형식이 없거나 크다
            B->>B: 「미리보기가 없어요」 와 내려받기
        end
    end
```

```mermaid
sequenceDiagram
    participant B as 브라우저
    participant W as Next.js 서버 라우트
    participant C as Control Plane
    participant H as 권한 도우미

    B->>W: GET /api/workspace 로 도는 실행 수를 다시 읽는다
    W->>C: GET /api/v1/workspace
    B->>B: 확인 창. 도는 실행이 있으면 다시 생길 수 있다고 알린다
    B->>W: DELETE /api/workspace/entries?path=
    W->>C: DELETE /api/v1/workspace/entries?path=
    C->>C: 경로 규칙, 읽기 마운트에서 있는지 확인
    C->>H: {owner, path, max_entries} 한 줄
    alt 지웠다
        H-->>C: ok, kind, entries, bytes
        C->>C: INFO 로그 한 줄
        C-->>B: 200. 목록을 다시 읽는다
    else 항목이 너무 많다
        H-->>C: TOO_MANY_ENTRIES
        C-->>B: 409. 아무것도 지우지 않았다고 알린다
    else 도우미가 실패했거나 답하지 않았다
        C-->>B: 502. 목록을 다시 읽어 남은 것을 보인다
    end
```

상태와 목록은 브라우저가 읽고, 실패는 그 자리의 다시 읽기로 다룬다.
두 탭에서 같은 것을 지우면 늦은 쪽은 404 를 받고 목록을 다시 읽는다.
목록을 연 사이 에이전트가 파일을 바꾸면 다음 읽기에 보인다. 화면은 스스로 다시 읽지 않는다.
