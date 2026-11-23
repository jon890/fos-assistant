# Agent Runtime 추상화

Control Plane 이 Hermes 를 부르는 실행 경계를 port 로 모으고, 다른 Agent Runtime 을 붙여 비교하는 기능이다.

covers: `backend/src/main/java/com/bifos/assistant/runtime/`, `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesAgentRuntime.java`, `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunEventKinds.java`, `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunOutcomes.java`

실행 port 가 `runtime` 패키지의 중립 계약(`AgentRuntime`)으로 옮겨졌고 구현은 Hermes 하나(`HermesAgentRuntime`)다. 실행을 부르는 곳은 `AgentRuntimes` 로 대상의 런타임 구현을 골라 부른다. 다른 런타임은 아직 붙지 않았다. 요구와 결정은 스펙 [`runtime-abstraction/spec.md`](runtime-abstraction/spec.md) 가 갖는다.
지금 실행이 Hermes 로 가는 흐름은 [실행 기록과 한도](execution.md)와 [Hermes 계약](../../hermes/docs/hermes-contract.md)이 갖는다.

이 파일의 「조사」 와 「하네스 설계 원칙 비교」 는 스펙을 정하려고 읽은 것을 남긴 기록이다. 비교 결과(평가 숫자와 왕복 확인)는 구현 PR 이 이 파일에 추가한다.

## 지금 Control Plane 이 Hermes 에 기대는 곳

port 가 무엇을 감싸야 하는지 정하려고 지금 기대는 곳을 모았다.

| 묶음 | 지금 하는 일 | 부르는 port |
| --- | --- | --- |
| 실행 | turn 제출, 사건 스트림, 끝난 결과, 중지, 글 끼워 넣기, 실행 조회, 잃은 실행 해제, session 삭제, 실제로 돈 모델 읽기 | `AgentRuntime`. Hermes 구현은 `HermesAgentRuntime` 이고 그 안에서 `HermesRunsClient` 를 부른다 |
| 지침과 기억 | 실행마다 `instructions` 에 공통 답변 지침과 요청자가 볼 수 있는 Memory 를 넣는다 | `RunCommand` |
| Control Plane 도구 | Hermes 가 Control Plane MCP 서버를 부르고, `fos-ctx` plugin 이 실행 맥락을 서명해 요청자를 정한다([MCP 와 토큰](mcp.md)) | `hermes/plugins/fos-ctx/` |
| 플러그인과 승인 | 플러그인 설치와 바인딩, 보관 파일, 도구 호출 판정과 승인 카드 | `HermesConnectorClient`, `fos-ctx` hook |
| 위임 | Control Plane 이 맡기는 위임과 Hermes 내장 하위 에이전트, 그 사용량 | `AgentRuntime`, 사건 스트림 |
| profile | profile 생성과 삭제, 성격 글, 환경 값 | `HermesDashboardClient` |
| 도구와 스킬 | toolset 목록과 켜기, 스킬 목록과 게시 | `HermesToolsetClient`, `HermesSkillClient` |
| 실행 공간 | 셸과 파일 도구가 사용자별 Docker 실행 공간에서 돈다 | profile 설정 |
| 모델 | provider 와 모델 목록, 실행마다 모델 지정, reasoning effort | `RunCommand`, 모델 조회 |
| 그 밖 | gateway 기동 시각, 플러그인 사건 목록 | `HermesGatewayClient`, `HermesEventFeedClient` |

## 조사

Codex app-server 의 동작은 2026-11-21 에 공식 문서([app-server](https://learn.chatgpt.com/docs/app-server))를 읽은 것이고 직접 돌려 확인하지 않았다.

### 실행 port 의 모양

실행은 `HermesRunsClient` 하나에 모여 있고 부르는 곳은 대화 turn, 중지, 글 끼워 넣기, 재기동 복구, 위임, 사용량 정리, 대화 삭제다.
실행 조회는 이미 Hermes 의 상태 문자열을 application 에 넘기지 않는다([Hermes 버전 올리기](hermes-upgrade.md) 의 「현재 실행 조회」). 끝난 결과의 상태 문자열은 application 이 읽어 취소인지 보고 실행 기록의 오류 코드로 저장한다. port 에는 그 밖에도 Hermes 에만 있는 것이 남아 있다.

| port 에 남은 Hermes 전용 | 어디 | 다른 런타임에서 |
| --- | --- | --- |
| 호출마다 넘기는 `apiBaseUrl` 과 `profileName` | 모든 메서드 | 런타임마다 격리 단위와 주소가 다르다. 「어느 런타임의 어느 격리 단위」 를 가리키는 값 하나로 바꿔야 한다 |
| 사건의 `type` 이 Hermes 사건 이름 | `RunEvent` | 런타임 구현이 공통 사건으로 옮겨야 한다 |
| 재기동으로 잃은 실행 해제 | `releaseIfLost` | Codex 에는 같은 개념이 없다. 기본 구현이 아무것도 하지 않으므로 그대로 둘 수 있다 |
| 끼우지 못한 글을 끝난 결과로 돌려받기 | `HermesRunResult.pendingSteer` | Codex 는 `turn/steer` 가 받은 turn 번호만 돌려준다. 못 끼운 글을 돌려주는지는 확인하지 못했다 |
| provider 와 모델을 함께 넘기기 | `HermesRunCommand` | Codex 는 모델만 받는다 |

Hermes 실행과 Codex app-server 가 짝이 맞는 곳은 이렇다.

| Control Plane 이 하는 일 | Hermes | Codex app-server |
| --- | --- | --- |
| 대화 하나를 이어 가는 단위 | session | thread(`thread/start`, `thread/resume`) |
| turn 제출 | `POST /v1/runs` | `turn/start` |
| 사건 받기 | 실행마다 SSE 를 연다 | 연결 하나로 모든 thread 의 알림이 온다 |
| 글 끼워 넣기 | `steer` | `turn/steer` |
| 중지 | `stop` | `turn/interrupt`. turn 이 `interrupted` 로 끝난다 |
| 끝난 뒤 조회 | 실행 조회 | `thread/resume` 으로 저장된 thread 를 연다 |
| 승인 | plugin hook 이 Control Plane 에 묻는다 | 서버가 연결로 승인 요청을 보내고 답을 기다린다 |
| 사용량 | 끝난 결과의 토큰 | `thread/tokenUsage/updated` 알림 |
| 검색 | 도구 사건. 내장 검색은 사건이 오지 않는다 | `webSearch` 항목에 검색어와 동작이 온다 |

### Codex app-server 를 붙일 때 필요한 것

- **프로세스가 따로 떠 있어야 한다.** 기본 연결은 표준 입출력이고 WebSocket 은 공식 문서가 「실험 기능이고 지원하지 않는다」 고 적는다. Control Plane 과 Codex 사이에 프로세스를 띄우고 연결을 붙드는 중계가 필요하다. 유닉스 소켓 연결도 있다.
- **사용자 격리는 프로세스와 `CODEX_HOME` 으로 한다.** 로그인, 설정, thread 기록이 `CODEX_HOME` 하나에 모인다. 2026-11-17 조사가 읽은 가이드는 인스턴스 하나로 여러 사용자를 받지 말라고 한다. Hermes 의 profile 에 해당하는 것이 「사용자마다 `CODEX_HOME` 과 프로세스」 다.
- **Control Plane 도구를 부르는 요청자를 정하는 방법이 새로 필요하다.** 지금은 `fos-ctx` plugin 이 Hermes 안에서 실행 맥락을 서명한다([MCP 와 토큰](mcp.md)). Codex 에는 그 plugin 이 없다. 프로세스가 사용자마다 따로이므로 MCP 서버 설정에 그 사용자의 토큰을 넣는 길이 있지만, 어느 실행이 불렀는지까지 정하는 방법은 설계에서 확인해야 한다.
- **셸과 파일 도구를 어떻게 둘지 정해야 한다.** Codex 는 코딩 하네스라 명령 실행과 파일 변경이 기본 도구다. 지금 셸과 파일 도구는 사용자별 Docker 실행 공간에서만 돈다([ADR-086](../adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md)). Codex 쪽에서 끄거나 승인으로 막지 않으면 이 경계가 깨진다.
- **Codex 쪽에 없는 것**: 플러그인 설치와 보관 파일, 스킬 게시, toolset 설정, 예약 작업, 플러그인 사건. 모두 Hermes 대시보드 plugin 에 기대고 있어 옮기려면 각각 새로 만들어야 한다.
- **버전 계약**: 스키마를 뽑는 명령이 있어 버전마다 계약 테스트를 만들 수 있다고 2026-11-17 조사가 적었다. 이번에 읽은 문서 범위에서는 그 명령을 확인하지 못했다.

### 열린 Agent Runtime 후보

사용자 지시(2026-11-21)는 「최대한 열린 제품으로 개발하고 쉽게 갈아탈 수 있는 환경」 이다. 그래서 비교 대상을 Codex 하나로 두지 않고 넓혀 조사했다.
2026-11-21 에 각 제품의 공식 문서와 저장소를 읽은 것이고 직접 돌려 보지 않았다. 「확인 못 함」 은 읽은 범위에 없었다는 뜻이다. 2026-11-17 조사에서 가져온 칸은 (11-17) 로 표시한다.

| 후보 | 정체와 라이선스 | Control Plane 이 붙는 방법 | 세션, 중지, 끼워 넣기 | 도구, MCP, 스킬, 하위 에이전트 | 승인 | 예약 실행 | 모델 | 우리 port 에 맞추는 비용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Hermes(지금) | 개인 비서용 에이전트, MIT | HTTP 와 SSE. ACP 로도 뜬다(`hermes acp`) | 모두 있음 | 모두 있음 | 있음 | 있음 | 자유, ChatGPT 구독 포함 | 이미 붙어 있다. 마이너 버전마다 계약이 바뀐다 |
| Codex app-server | OpenAI Codex 하네스, Apache-2.0 (11-17) | 자체 JSON-RPC. 표준 입출력이 기본, WebSocket 은 실험 기능. ACP 는 어댑터로 | thread 재개, `turn/interrupt`, `turn/steer` | MCP, 웹 검색 사건 있음. 스킬과 하위 에이전트는 확인 못 함 | 서버가 승인을 요청 | 없음 | OpenAI 계열. ChatGPT 구독 로그인 | 사용자마다 프로세스를 띄우는 중계가 필요하다. 실행 메서드는 짝이 모두 맞는다 |
| OpenCode 서버 | 코딩 에이전트의 서버 모드, MIT | HTTP API 와 OpenAPI 문서. 문서가 「실험 단계」 라고 적는다. ACP 로도 뜬다 | 세션 생성·포크·중단, 대기 입력함, 자식 세션 | MCP 를 실행 중에 붙이고 뗀다. 스킬과 에이전트 목록 있음 | 승인 요청 목록과 답하기 API | 확인 못 함 | provider 를 고른다. 문서가 ChatGPT Plus 와 Pro 구독 로그인을 지원한다고 적는다. 서버에서 화면 없이 로그인되는지는 확인 못 함 | HTTP 라 중계가 얇다. API 가 실험 단계라 바뀔 수 있다 |
| Deep Agents | LangChain 의 하네스 라이브러리(Python). LangGraph 위에서 돈다 | 라이브러리라 HTTP 서버를 우리가 만든다 | LangGraph 의 저장과 재개, 스트리밍 | 하위 에이전트(`task` 도구), `SKILL.md` 스킬, 파일 backend, MCP 도구 | `interrupt_on` 으로 멈추고 승인 | 우리가 만든다 | 자유(OpenAI, Anthropic, Google, Ollama 등). ChatGPT 구독은 쓰지 못한다 | Python 서비스를 하나 새로 만들어 운영한다. 계약은 우리가 정한다 |
| LangGraph Agent Server | LangGraph 를 서버로 띄우는 제품. 핵심 라이브러리는 MIT, 서버는 Elastic License 2.0 | REST 와 스트림 | thread, 실행, 중단과 재개 | 우리가 조합 | `interrupt` | cron 있음 | 자유 | 자체 호스팅 서버가 기동할 때 라이선스 key 를 확인한다. 열린 제품이라는 기준에 맞지 않는다 |
| Agno AgentOS | 에이전트 프레임워크와 그 실행 서버. 우리 인프라에서 돈다. 라이선스는 이번에 확인 못 함 | REST 와 SSE | 세션을 우리 데이터베이스에 저장, 실행 취소 | MCP, 팀, 기억, 지식 | 승인 API 있음 | 스케줄러 있음 | 자유 | Python 서비스를 하나 운영한다. 에이전트 정의를 코드로 쓴다 |
| OpenHands Agent Server | 코딩 에이전트 SDK 의 서버, MIT (11-17) | HTTP 와 WebSocket, OpenAPI. ACP 로도 뜬다 | 대화 생성과 사건 스트림 | 작업 공간의 파일과 명령 중심. 나머지는 확인 못 함 | 확인 못 함 | 확인 못 함 | 자유 | 코딩 작업 공간 중심이라 비서 대화와 거리가 있다 |
| DeepSeek Harness | 모든 것이 플러그인인 하네스, MIT | 표준 입출력 JSON-RPC(`sdk`), ACP(`acp`), 한 번 실행(`headless`) | 세션 저장. 재개 방법은 확인 못 함 | MCP 는 추가 설정, 나머지는 확인 못 함 | 확인 못 함 | 확인 못 함 | 확인 못 함 | 저장소가 「개발자 미리보기이고 호환성을 깨는 변경이 있을 것」 이라고 적는다 |
| goose | Linux Foundation 아래 프로젝트, Apache-2.0 (11-17) | ACP. 자체 REST 서버는 ACP 로 옮기는 중 (11-17) | 확인 못 함 | MCP 확장 | 확인 못 함 | 확인 못 함 | 자유 | 서버 API 를 바꾸는 중이다 |
| OpenAI Agents SDK | 라이브러리, MIT (11-17) | 우리가 HTTP 서버를 만든다 | 실행 상태를 저장하고 재개 | MCP, handoff | 승인에서 멈추고 상태를 저장 | 우리가 만든다 | OpenAI API key. ChatGPT 구독은 쓰지 못한다 | 서비스를 하나 새로 만든다. API key 비용이 든다 |
| Claude Agent SDK | Claude Code 를 라이브러리로 씀, Anthropic 상용 약관 (11-17) | 라이브러리. ACP 는 어댑터로 | 재개와 포크 | MCP, 하위 에이전트, 스킬 | hook | 우리가 만든다 | Claude 만, API key | 열린 제품이 아니고 모델이 한 회사에 묶인다 |

Pydantic AI 와 Google ADK 는 이번에 읽지 못했다.

### 여러 런타임이 함께 말하는 프로토콜(ACP)

Agent Client Protocol(ACP)은 편집기가 코딩 에이전트를 부리려고 만든 JSON-RPC 프로토콜이다. 2026-11-21 기준 공식 목록에 에이전트 51개가 있고 위 후보 가운데 Hermes, OpenCode, OpenHands, goose 가 들어 있다. Codex 와 Claude Agent 는 어댑터로 붙는다. DeepSeek Harness 는 목록에는 없지만 자체 `acp` 모드가 있다.
Control Plane 이 ACP 로 말하는 구현 하나를 가지면 이 에이전트들을 구현을 새로 쓰지 않고 바꿔 끼울 수 있다. 「쉽게 갈아탈 수 있는 환경」 에 가장 가까운 길이다.

| 우리에게 필요한 것 | ACP |
| --- | --- |
| 대화 단위와 재개 | `session/new`, `session/load` |
| turn 제출과 답 흐름 | `session/prompt`, `session/update` 의 `agent_message_chunk` |
| 도구 사건 | `tool_call`, `tool_call_update` |
| 중지 | `session/cancel`. turn 이 `cancelled` 로 끝난다 |
| 승인 | 에이전트가 `session/request_permission` 으로 묻는다 |
| 사용량 | `usage_update` 에 쓴 토큰과 선택 칸인 비용 |
| Control Plane 도구 붙이기 | 세션을 만들 때 `mcpServers` 로 MCP 서버를 넘긴다. HTTP 서버는 헤더를 함께 넘긴다. 세션마다 다른 토큰을 줄 수 있어 요청자를 정하는 문제가 쉬워진다 |
| 지침 넣기 | `systemPrompt`. 에이전트가 지원한다고 알릴 때만 쓴다 |
| 하위 에이전트 사건 | `subagent_update`. 선택 기능이다 |

ACP 에 모자란 것도 있다.

- **확정된 연결은 표준 입출력뿐이다.** HTTP 와 WebSocket 연결은 2026-07 에 제안이 활성 단계가 됐고 기준 구현이 아직 없다. Codex app-server 와 마찬가지로 프로세스를 띄우는 중계가 필요하다.
- **도는 turn 에 글을 끼워 넣는 표준이 없다.** 제안 단계다. 그동안은 지금 port 의 「끼워 넣기를 모르는 구현」 처럼 다음 turn 으로 넘겨야 한다([ADR-048](../adr/ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md)).
- **코딩 에이전트를 전제로 한다.** 세션에 작업 디렉터리가 꼭 있고 에이전트가 자기 셸과 파일 도구를 쓴다. 이 도구를 끄거나 격리하는 방법은 에이전트마다 다르다.
- **어댑터를 거치면 그 런타임만의 기능이 빠진다.** Codex 의 `turn/steer` 와 검색어 사건이 ACP 어댑터로도 오는지 확인하지 못했다.

### 구독 로그인 토큰을 두는 곳

Codex app-server 의 로그인 방식은 넷이다(`account/login/start`).

| 방식 | 동작 | 이 기능에서 |
| --- | --- | --- |
| API key | key 를 넘긴다 | 쓰지 않는다(스펙 DECISION1) |
| 브라우저 로그인 | Codex 가 자기 머신에서 콜백을 받는다 | 서버에서 도는 프로세스라 사용자의 브라우저가 콜백에 닿지 못한다 |
| 기기 코드 로그인 | 화면에 주소와 코드를 보이고 사용자가 다른 기기에서 입력한다. Codex 가 토큰을 `CODEX_HOME` 에 저장하고 스스로 갱신한다 | 서버에서 도는 프로세스에 맞는다 |
| 밖에서 넣는 토큰(실험 기능) | 호스트가 access token 을 넣고, 만료되면 Codex 가 호스트에 새 토큰을 요청한다. 약 10초 안에 답해야 한다 | Control Plane 이 토큰을 보관하고 갱신까지 맡아야 한다 |

토큰을 두는 곳의 후보는 둘이다.

| 후보 | 얻는 것 | 치르는 것 |
| --- | --- | --- |
| Codex 가 `CODEX_HOME` 의 파일로 갖는다(기기 코드 로그인) | Hermes 의 구독 로그인 파일과 같은 취급이다. [서버 비밀값 스펙](secret-store/spec.md)이 그 파일을 「사람이 로그인해 런타임이 만드는 파일」 로 보고 파일에 남겼다. 갱신을 Codex 가 한다 | 토큰이 데이터베이스 밖 파일에 있다. 백업과 권한을 그 디렉터리 단위로 챙겨야 한다 |
| Control Plane 이 `server_secret` 에 감싸 두고 넣어 준다(밖에서 넣는 토큰) | 비밀값이 한곳에 모이고 `CODEX_HOME` 에 토큰이 남지 않는다 | 실험 기능에 기댄다. 로그인과 갱신 흐름을 Control Plane 이 직접 만들어야 하고, 그 흐름이 구독 로그인에 허용되는지 확인하지 못했다 |

지금 Hermes 는 그룹이 구독 하나를 함께 쓴다([Hermes 계약](../../hermes/docs/hermes-contract.md#oauth-credential-은-여기서-빠진다)). 사용자마다 `CODEX_HOME` 을 나누면 같은 구독으로 사용자마다 따로 로그인하거나, 로그인 하나를 여러 `CODEX_HOME` 이 함께 쓰는 방법을 정해야 한다.
ChatGPT 구독 로그인을 가족용 서비스에 쓰는 것이 약관에 맞는지는 2026-11-17 조사에서도 확인하지 못했다.

## 일상생활 비서용 하네스 후보

사용자 요청(2026-11-21)은 「Hermes 와 같은 다른 하네스가 없는지, 일상용 비서로 쓸 만한 에이전트 하네스가 필요하다」 다. 그래서 비교 대상을 코딩 에이전트가 아니라 개인 비서용으로 만든 자체 호스팅 하네스에서 다시 찾았다.
2026-11-21 에 각 제품의 공식 문서와 저장소를 읽은 것이고 직접 돌려 보지 않았다. 「확인 못 함」 은 읽은 범위에 없었다는 뜻이다.

| 후보 | 정체와 라이선스 | 문서가 적은 설계 원칙 | 기억 | 예약 | 채널 | 플러그인과 MCP | 하위 에이전트 | 승인과 격리 | 모델 | Control Plane 이 붙는 방법 | 우리 port 에 맞추는 비용 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Hermes(지금) | 개인 비서 에이전트, MIT, Python | 대화 중에 시스템 프롬프트를 바꾸지 않는다. profile 마다 설정과 기억과 세션을 나눈다 | 한도가 있는 기억 파일 둘, 지난 세션 검색 | cron | gateway 하나로 20개 넘게 | 스킬, plugin, MCP | 있음 | 명령 승인, 컨테이너 backend | 자유, ChatGPT 구독 | HTTP 와 SSE, ACP | 이미 붙어 있다 |
| OpenClaw | 자기 머신에서 돌리는 비서, MIT, TypeScript. 비영리 재단이 관리하고 유료 등급이 없다 | 오래 떠 있는 Gateway 하나가 모든 채널 연결과 세션을 갖는다. 들어오는 메시지는 믿지 않는 입력으로 본다. 에이전트 루프를 밖의 하네스에 넘기지 않고 안에 갖는다 | 작업 공간의 `MEMORY.md`, `USER.md`, `SOUL.md` 를 시스템 프롬프트에 넣는다 | Gateway 의 스케줄러(cron) | WhatsApp, Telegram, Slack, Discord, Signal, 웹 대화 등 | 스킬, plugin. MCP 서버는 Gateway 나 에이전트 설정에 적는다 | 배경에서 도는 자기 세션으로 띄우고 결과를 요청한 쪽에 알린다 | 에이전트마다 작업 공간, 로그인, 세션, 샌드박스, 도구 허용 목록. 문서가 작업 공간은 「단단한 샌드박스가 아니다」 라고 적고, 서로 믿지 않는 사용자는 Gateway 를 따로 띄우라고 한다 | provider 를 고른다. ChatGPT 구독 로그인, 화면 없는 머신용 기기 코드 로그인 | ACP 다리(`openclaw acp`, 표준 입출력), OpenAI 호환 HTTP(기본 꺼짐), WebSocket | ACP 구현으로 붙는다. 다만 ACP 다리는 세션마다 넘기는 MCP 서버를 거절하고, 모델 고르기를 내주지 않으며, 사용량은 어림값이고, `session/load` 는 일부만 된다. 답 중에 보낸 글을 그 turn 에 끼우는 것이 기본 동작이다. 사용자 격리는 Gateway 를 사용자마다 띄워야 한다. HTTP 는 요청마다 지침을 넣는 칸이 문서에 없고 토큰 하나가 Gateway 전체 권한이다 |
| ZeroClaw | 「빠르고 작은 개인 비서 인프라」, MIT 또는 Apache-2.0, Rust 바이너리 하나 | 「에이전트도 데이터도 머신도 내 것」. 기본 자율 수준이 「감독」 이라 위험이 중간인 동작은 승인을 받고 높은 동작은 막는다 | SQLite 와 임베딩 | cron, 사건으로 시작하는 절차 | Discord, Telegram, Matrix, 메일, webhook 등 30개 넘게 | MCP 서버. 스킬은 확인 못 함 | 확인 못 함 | 운영체제 샌드박스(Landlock, Bubblewrap, Docker 등), 동작마다 서명한 기록 | provider 20여 개와 OpenAI 호환 주소. Codex 구독 로그인 | ACP(표준 입출력), HTTP 와 WebSocket gateway | ACP 구현으로 붙는다. 여러 사용자 격리는 확인 못 함 |
| nanobot | 「가볍고 자체 호스팅하는 개인 AI 에이전트」, MIT, Python(홍콩대 연구실) | 작은 코어. gateway 하나가 세션, 도구, 기억, 자동화, 보안 정책을 한곳에서 다룬다 | 장기 기억 「Dream」 | 알림과 반복 보고, cron 이 할 일 목록을 확인 | 15개 넘게 | MCP, 스킬과 스킬 장터 | 모델이 부르는 도구로 있음 | 명령 샌드박스와 작업 공간 권한. 세부는 확인 못 함 | API, 구독 로그인(OpenAI Codex 포함), 로컬 모델 | OpenAI 호환 HTTP(`nanobot serve`). ACP 는 확인 못 함 | ACP 가 없어 구현이 따로 든다. HTTP 는 요청에 사용자 메시지 하나만 받고 기본 실행 한도가 120초다. 세션 id 에 접근 통제가 없어 믿는 경계마다 인스턴스를 따로 띄우라고 한다 |
| NanoClaw | OpenClaw 보다 작게 만든 비서, MIT | 「이해할 수 있을 만큼 작게」, 「바꾸려면 코드를 고친다」, 기능 대신 스킬 | 에이전트 묶음마다 기억과 지침 파일 | 한 번과 반복 작업 | 스킬로 추가 | MCP | 대화에서 동료 에이전트를 띄운다 | 에이전트마다 Linux 컨테이너 | 기본은 Claude Agent SDK. Codex, OpenCode 도 고른다 | HTTP API 와 ACP 를 찾지 못했다. 대화와 CLI 로 다룬다 | 밖에서 부릴 길이 없어 맞추기 어렵다. 그 자체가 다른 하네스를 감싼 층이다 |
| goose | 범용 에이전트, Apache-2.0 | MCP 로 넓힌다 | 확인 못 함 | 확인 못 함 | 데스크톱과 CLI | MCP | 확인 못 함 | 확인 못 함 | provider 85개 넘게 | ACP | ACP 구현으로 붙는다. 비서 기능은 우리가 채운다 |
| Letta | 기억을 가진 에이전트 플랫폼. 라이선스는 확인 못 함 | 「에이전트가 경험에서 배우고 쓸수록 나아진다」 | 기억과 「dreaming」 | 반복 프롬프트 | Slack, Telegram 등을 자기 인프라에서 | 스킬, MCP | 확인 못 함 | 권한 문서가 있다. 세부는 확인 못 함 | provider 를 고른다. ChatGPT 구독은 확인 못 함 | 자체 서버와 SDK. 세부는 확인 못 함 | 확인 못 함 |

목록 글에 이름만 나온 것(PicoClaw, OpenHuman, Vellum, TrustClaw)은 읽지 못했다.

읽은 범위에서 Hermes 와 같은 종류는 OpenClaw, ZeroClaw, nanobot 이다. 셋 모두 메신저 gateway, 기억, 예약, 도구 플러그인을 갖고 자기 머신에서 돈다.
이 종류의 하네스는 기억, 예약, 채널, 사용자 구분을 스스로 갖는다. 우리 서비스에서는 그 몫을 Control Plane 이 가지므로([Memory 접근](memory.md), [예약 작업](schedule.md)) 지금 Hermes 에서 그러듯 하네스의 그 기능은 끄거나 쓰지 않고 실행 루프와 도구 호출만 쓴다.
세 후보 모두 한 사람이나 서로 믿는 사람들이 쓰는 것을 전제로 한다. 사용자마다 격리하려면 인스턴스를 사용자마다 띄워야 한다는 점이 Hermes 의 profile 과 다르다.
OpenClaw 와 ZeroClaw 는 ACP 로 뜨므로 스펙이 정한 ACP 구현 하나로 붙일 수 있다. 사용자는 이 둘을 첫 비교 대상으로 정했다(스펙 DECISION3).

OpenClaw 의 ACP 다리 문서가 적은 한계는 설계에서 먼저 확인해야 한다.

- 세션을 만들 때 넘기는 MCP 서버를 받지 않는다. Control Plane 도구는 Gateway 나 에이전트 설정에 미리 적어야 한다. 사용자마다 Gateway 를 따로 띄우므로 사용자는 설정으로 정해지지만, 어느 실행이 불렀는지는 다른 방법으로 알려야 한다
- 모델을 ACP 로 고르지 못한다. 에이전트 설정이 정한다
- 사용량은 어림값이고 비용이 없다
- 명령 실행 승인은 도는 turn 안에서만 `session/request_permission` 으로 넘어온다
- 지침을 넣는 `systemPrompt` 를 받는지는 문서에 없다

## 하네스 설계 원칙 비교

사용자 요청(2026-11-21)은 「다른 하네스는 어떤 원칙으로 개발했는지, 일상생활 비서에 더 맞는 하네스인지」 다.
2026-11-21 에 각 제품의 공식 문서를 읽은 것이고 직접 돌려 보지 않았다. 「확인 못 함」 은 읽은 범위에 없었다는 뜻이다.
답 품질은 이 글로 정하지 않는다. 같은 평가 세트를 런타임마다 돌린 숫자로 본다(스펙의 REQ13, REQ17).

### 비교 대상: Hermes, OpenClaw, ZeroClaw

| 축 | Hermes(지금) | OpenClaw | ZeroClaw |
| --- | --- | --- | --- |
| 스스로 밝힌 성격 | 「오래 돌수록 능력이 느는 자율 에이전트」 | 자기 머신에서 돌며 이미 쓰는 메신저에서 답하는 비서 | 「빠르고 작은 개인 비서 인프라」 |
| 문서가 적은 설계 원칙 | 대화 중에 시스템 프롬프트를 바꾸지 않는다. 모든 도구 호출이 사용자에게 보인다. 도중에 멈출 수 있다. profile 마다 설정, 기억, 세션을 나눈다 | Gateway 하나가 모든 채널 연결을 갖는다. 들어오는 메시지는 믿지 않는 입력으로 본다. 사건은 다시 보내 주지 않으므로 클라이언트가 빈 곳을 새로 읽는다. 에이전트 루프를 안에 갖는다 | 「에이전트도 데이터도 머신도 내 것」. 철학 문서가 따로 있으나 읽지 못했다 |
| 지침 구조 | 변하지 않는 것, 맥락 파일, 변하는 것(기억, 시각) 순서. 성격은 `SOUL.md` | 작업 공간의 `AGENTS.md`(지침), `SOUL.md`(성격), `IDENTITY.md`, `USER.md`, `MEMORY.md` 를 시스템 프롬프트에 넣는다. 큰 파일은 잘라 넣는다 | 확인 못 함 |
| 도구 호출 | 중앙 registry, toolset 단위로 켠다 | 기본 도구에 에이전트마다 허용과 거부 목록을 건다. 스킬은 작업 공간 것이 가장 앞선다 | MCP 서버와 내장 도구. 동작마다 서명한 기록을 남긴다 |
| 기억 | 한도가 있는 파일 둘을 세션 시작 때 고정해 넣는다. 지난 세션 검색 | `MEMORY.md` 를 넣는다. 압축 전에 조용한 turn 을 돌려 남길 것을 기억에 적는다 | SQLite 와 임베딩 |
| 긴 대화의 맥락 | 가운데 turn 을 요약한다 | 한도에 가까워지면 옛 turn 을 요약하고 최근 메시지는 둔다. 전체 기록은 디스크에 남는다. 도구 결과만 줄이는 가벼운 방식이 따로 있다 | 확인 못 함 |
| 다시 시도와 실패 처리 | 실행 루프가 재시도와 대체 모델을 맡는다 | 맥락 초과 오류는 압축하고 다시 시도한다. 요약이 실패하면 대체 모델 순서로 다시 시도하고, 그래도 안 되면 기록을 그대로 둔다 | provider 대체 순서. 스트리밍이 실패하면 스트리밍 없이 다시 부른다 |
| 하위 에이전트 | 새 대화, 기본 한 단계, 마지막 요약만 부모에게 | 자기 세션에서 배경으로 돌고 결과를 요청한 쪽에 알린다. 기본으로 세션과 메시지 도구를 받지 못한다. 깊이와 수는 설정한다 | 확인 못 함 |
| 답 중에 보낸 글 | 끼워 넣기(`steer`) | 기본이 끼워 넣기다. 도구 묶음이 끝나고 다음 모델 호출 전에 확인한다. 다음 turn 으로 미루는 방식도 있다 | 확인 못 함 |
| 안전 장치 | 명령 승인, 컨테이너 backend | 모르는 발신자는 짝 맺기 승인, 에이전트마다 샌드박스. 설정하지 않으면 도구가 호스트에서 돈다 | 기본 자율 수준 「감독」: 위험이 중간이면 승인, 높으면 막는다. 운영체제 샌드박스 |
| 사용자 격리 | profile | 에이전트마다 작업 공간과 로그인과 세션. 서로 믿지 않으면 Gateway 를 따로 | 확인 못 함 |

### 참고: 코딩 하네스 둘

처음에는 Codex 와 OpenCode 를 비교 대상으로 조사했다. 사용자가 코딩 에이전트를 비교 대상에서 뺐으므로(2026-11-21) 아래 표는 참고로만 둔다.

| 축 | Hermes(지금) | Codex | OpenCode |
| --- | --- | --- | --- |
| 스스로 밝힌 성격 | 코딩 도우미가 아니라 「오래 돌수록 능력이 느는 자율 에이전트」. 메신저 gateway, 예약 실행, 기억, 스킬을 함께 갖는다 | 코딩 에이전트. 샌드박스 안에서 스스로 명령을 돌리고 파일을 고친다 | 「오픈소스 AI 코딩 에이전트」. provider 를 고르고 서버와 클라이언트가 나뉜다 |
| 문서가 적은 설계 원칙 | 대화 중에 시스템 프롬프트를 바꾸지 않는다. 모든 도구 호출이 사용자에게 보인다. 호출과 도구 실행을 도중에 멈출 수 있다. 한 에이전트 class 가 CLI, gateway, ACP, API server 를 모두 받는다. profile 마다 설정, 기억, 세션을 나눈다 | 요청마다 대화 전체를 보내 서버에 상태를 두지 않는다. 프롬프트 앞부분을 고치지 않고 뒤에 덧붙여 캐시를 지킨다. 「에이전트의 의도를 믿는 것이 아니라 강제된 경계 안에서 돈다는 것을 믿는다」 | 원칙을 따로 적은 글은 확인 못 함. 도구 접근을 `tools` 목록이 아니라 권한 규칙으로 다룬다 |
| 지침 구조 | 변하지 않는 것(정체, 도구 안내, 스킬), 맥락 파일, 변하는 것(기억, profile, 시각) 순서로 쌓는다. 성격은 `SOUL.md` 가 정한다 | 모델마다 딸린 기본 지침, 도구 목록, 입력 순서다. 입력에는 샌드박스와 권한 안내, 개발자 지침, `AGENTS.md` 를 모은 글(기본 32 KiB 까지), 작업 디렉터리 같은 환경 맥락, 사용자 요청이 차례로 든다 | 에이전트나 provider 의 기본 프롬프트, 도구 안내, MCP 와 스킬 안내, `AGENTS.md`, 설정의 `instructions`, 날짜와 환경 순서다. 에이전트에 `system` 을 적으면 기본 프롬프트를 대신한다 |
| 도구 호출 | 중앙 registry 에 도구가 스스로 등록하고 toolset 단위로 켠다. 여러 단계를 코드 한 번으로 묶는 `execute_code` 가 있다 | 셸, 계획 도구, 내장 웹 검색, MCP 도구. 샌드박스가 감싸는 것은 셸뿐이고 MCP 도구는 스스로 막아야 한다 | 권한 규칙이 `allow`, `ask`, `deny` 를 정하고 마지막에 맞은 규칙이 이긴다. 에이전트마다 모델 step 상한을 두고, 마지막 step 에서는 도구를 빼고 글로 정리하게 한다 |
| 기억 | `MEMORY.md`(2,200자)와 `USER.md`(1,375자)를 세션을 시작할 때 고정해 넣는다. 자동으로 줄이지 않아 넘치면 에이전트가 정리한다. 지난 세션은 전문 검색으로 찾는다 | 기억 기능 문서가 있으나 읽지 못했다 | 세션을 넘는 기억이 없다. 지침 파일만 이어진다 |
| 긴 대화의 맥락 | 기준을 넘으면 가운데 turn 을 요약한다(내용이 빠질 수 있다). 긴 대화는 새 세션으로 끊으라고 권한다 | 기준을 넘으면 자동으로 압축 항목 하나로 줄인다 | 압축을 맡는 숨은 에이전트가 있다. 세부는 확인 못 함 |
| 다시 시도와 실패 처리 | 실행 루프가 재시도와 대체 모델을 맡는다고만 적는다. 하위 에이전트는 진행이 없으면(450초, 도구 안에서는 1,200초) 끊고, 반복 250회에서 멈춘다 | 확인 못 함 | 확인 못 함. 지침 파일 읽기가 잠깐 실패하면 마지막으로 읽은 내용을 그대로 쓴다 |
| 하위 에이전트 | 새 대화로 시작하고 부모가 넘긴 목표와 맥락만 받는다. 기본은 한 단계이고 한 번에 10개까지 띄운다. 자식은 기억, 메시지 보내기, 예약 도구를 쓰지 못한다. 마지막 요약만 부모에게 가고, 배경으로 돈 결과는 부모의 turn 사이에 전달된다 | 문서가 있으나 읽지 못했다 | 자식 세션에서 새 맥락으로 돌고 늘 배경으로 돈다. 자식이 멈추면 부모가 보고를 받는다. 부모의 권한 규칙이 띄울 수 있는 에이전트를 정한다 |
| 안전 장치 | 명령 승인, 터미널을 컨테이너에 가두는 backend, 기억 쓰기 승인 | 샌드박스(읽기 전용, 작업 공간 쓰기, 제한 없음)와 승인 정책(경계를 넘을 때 묻기, 묻지 않기)을 따로 둔다. 승인은 「일을 이어 갈 수 있는 가장 좁은 범위」 로 고르라고 한다 | 권한 규칙과, 파일을 고치지 않는 `plan` 에이전트 |
| 로그인과 모델 | provider 를 고른다. ChatGPT 구독 로그인 포함 | OpenAI 모델. ChatGPT 구독 로그인 | provider 를 고른다. ChatGPT Plus 와 Pro 구독 로그인 |

### 그 밖의 후보

| 후보 | 설계에서 눈에 띄는 것 | 스스로 밝힌 쓰임새 |
| --- | --- | --- |
| Deep Agents | 가상 파일 시스템에 큰 결과를 내려놓아 맥락을 비운다. 하위 에이전트로 맥락을 나눈다. 기억은 `AGENTS.md`, 스킬은 필요할 때만 전문을 읽는다. 민감한 도구 앞에서 멈춰 승인을 받는다(`interrupt_on`) | 여러 단계에 걸쳐 오래 도는 작업 |
| goose | MCP 로 도구를 붙인다. ACP 로 다른 앱이 부린다. provider 를 85개 넘게 받는다 | 「코딩, 조사, 글쓰기, 자동화, 데이터 분석을 하는 범용 에이전트」 |
| Agno | 기억, 지식, 세션을 PostgreSQL 에 둔다. 팀과 워크플로, 민감한 동작 앞의 승인, 스케줄을 실행 서버가 갖는다 | 자기 인프라에서 돌리는 에이전트 서비스 |
| OpenHands | 보안 분석과 자동 맥락 압축을 내세운다 | 「소프트웨어 엔지니어링을 위해 만들었다」 고 적는다 |
| OpenAI Agents SDK | 「쓸 만큼 기능이 있되 금방 배울 만큼 기본 요소가 적다」. 기본 요소는 에이전트, handoff, guardrail 이다 | 라이브러리로 직접 조립 |
| DeepSeek Harness | 모든 기능을 플러그인으로 둔다 | 개발자 미리보기 |

### 일상생활 비서에 맞는지

읽은 범위에서 일상생활 비서를 목표로 만든 것은 Hermes, OpenClaw, ZeroClaw, nanobot 이고, goose 가 범용을 내세운다. Codex, OpenCode, OpenHands 는 코딩을 목표로 만들었다.
코딩 하네스는 작업 디렉터리와 셸, 파일 도구를 전제로 한다. 비서 대화에는 이 도구가 쓸모보다 위험이 커서 끄거나 막아야 한다.

일상생활 비서에 필요한 것 가운데 많은 부분은 이미 Control Plane 이 갖는다.

| 비서에 필요한 것 | 지금 가진 곳 | 런타임에 남는 몫 |
| --- | --- | --- |
| 사용자마다 다른 기억과 그 권한 | Control Plane([Memory 접근](memory.md)) | 받은 요약을 지침으로 읽고 도구로 더 읽기 |
| 예약 작업 | Control Plane([예약 작업](schedule.md)) | 정해진 시각에 받은 turn 을 돌리기 |
| 전문 에이전트에게 맡기기 | Control Plane([에이전트 팀](agent-team.md)) | 맡기는 도구를 부르기 |
| 승인 카드 | Control Plane | 승인이 필요할 때 멈추고 묻기 |
| 쇼핑 같은 플러그인 도구 | Hermes 에 설치하는 플러그인([플러그인 연결](connector.md)) | Hermes 에 묶여 있다. 다른 런타임에서는 쓰지 못한다 |
| 최신 정보 검색과 출처 | 런타임 | 검색 도구의 품질, 검색어를 사건으로 알려 주는지 |
| 긴 대화에서 맥락 지키기 | 런타임 | 압축 방식 |
| 사진 읽기 | 런타임과 모델 | 이미지 입력 |
| 답의 말투와 형식 | Control Plane 이 넣는 공통 지침 | 런타임의 기본 지침이 우리 지침과 부딪치지 않는지 |

그래서 런타임을 바꿨을 때 달라질 수 있는 것은 검색과 답의 품질, 긴 대화의 맥락 관리, 기본 지침의 성격, 계약이 얼마나 자주 바뀌는지다.
비서용 하네스는 기본 지침과 성격 파일이 비서 대화를 전제로 쓰여 있어 코딩 하네스보다 우리 공통 지침과 부딪칠 일이 적을 것으로 보이지만, 문서로만 본 것이라 평가로 확인해야 한다.
