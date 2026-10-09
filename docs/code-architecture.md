# 구조

## 경계

```
브라우저
  └ Next.js (web/)            세션은 여기까지만 산다
      └ 서버 라우트에서 짧은 수명의 토큰을 발급해 호출
          └ Spring Boot (backend/)   Control Plane
              └ Hermes API server    /p/<profile>/v1/runs
```

브라우저는 Control Plane 토큰을 갖지 않는다.
Next.js 서버 라우트가 세션에서 메일 주소를 꺼내 매 요청마다 토큰을 새로 만든다.
그래서 사용자가 요청 본문을 고쳐 남의 자료를 달라고 할 수 없다.

**위 그림은 요청이 나가는 쪽만 그린 것이다.**
Hermes 가 Control Plane 을 부르는 반대 방향도 있고 토큰이 서로 다르다.
그 두 방향은 [`flow.md`](flow.md) 의 「두 방향과 두 토큰」 절이 그림으로 갖는다.

## 사용자와 profile, 에이전트, 대화

층이 넷이고 서로 다르다.

```
사용자 (app_user)
 └ 그 사용자의 profile 들
     └ 각 profile 을 가리키는 에이전트 (agent 표)
         └ 그 에이전트로 시작한 대화 (conversation)
```

**한 사용자가 profile 을 여럿 가질 수 있다.**
기본 profile 하나에 역할 profile 을 더한다.
둘은 만드는 방법이 달라 설정도 다르다.
`fos-home-infra` 가 그 차이를 소유한다.

## Hermes 쪽 코드 (`hermes/`)

Control Plane 이 기대는 Hermes 쪽 코드는 이 저장소가 갖는다.
근거는 [ADR-041](adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md) 에 있다.

```
hermes/
  plugins/
    dashboard-profile-api/   대시보드 plugin. profile 만들기와 지우기, env, 도구와 스킬 설정, 커넥터
      __init__.py             register 와 기존 이름 다시 내보내기
      common.py, profiles.py, session.py
      sandbox.py, sandbox_approvals.py, toolconfig.py, env.py
      connector_schema.py, connector_policy.py, connector_skills.py, connector_appearance.py
      connector_manifest.py, connector_vault.py
      connector_state.py, connector_status.py
      connector_isolated.py, connector_binding.py, connector_install.py
      connector_mcp.py, connector_run.py, connector_output.py
      routes.py               인증 provider 와 토큰 경로 연결
    fos-ctx/                 profile plugin. Control Plane MCP 호출에 _fos_ctx 서명을 붙이고, 바인딩 profile 과 옛 설치 profile 의 커넥터 도구 호출을 Control Plane 에 물어 막는다
      __init__.py             register 와 기존 이름 다시 내보내기
      context.py              서명과 루트 session, 호출 문맥
      connector_policy.py     이름 대응과 정책 질의
      subagent.py, hooks.py   자식 등록과 hook 진입점
  connectors/
    <커넥터 이름>/            범용 커넥터 하나. connector.json, .mcp.json, MCP 서버, 스킬 (ADR-064)
  profile-template/
    config.yaml.template     새 profile 의 설정 틀. 안전한 도구 목록, Control Plane MCP 등록, fos-ctx 켜기
  bundle.sh                  설치 묶음을 만든다
  tests/                     Python unittest. Hermes 모듈은 가짜로 끼운다
```

**서비스 이름은 `hermes/connectors/` 와 그 검사와 문서에만 둔다.** `backend/src/main`, `web/src`, `hermes/plugins` 는 어느 서비스도 모른다. `test/unit/connector-neutral.test.ts` 가 본다.

**운영 값은 코드에 두지 않는다.** 설치 묶음을 만들 때와 프로세스의 환경 변수로 받는다.
묶음을 Hermes 에 넣고 대시보드를 다시 띄우는 것은 운영 저장소가 한다.
설치 묶음의 모양, 운영 값의 목록, 검사 방법은 [`hermes/README.md`](../hermes/README.md) 가 갖는다.

**plugin 은 한 배포 동안 옛 Control Plane 의 호출도 받는다.** 운영은 plugin 을 먼저 올리고 Control Plane 을 올린다.
경로나 요청 모양을 바꿀 때는 새 것을 더하고, 옛 것은 그다음 배포에서 뺀다.

## Memory 에서 아직 만들지 않은 것

아래는 아직 만들지 않았다. 스키마와 판정은 이미 받을 수 있게 되어 있다.

- collection 탭, 문서의 판 이력 화면, 출처 표시
- 신원 항목의 들이기. 암호화와 문서 읽기 경계와 `identity` 권한을 운영에서 확인한 뒤에 연다. 조건은 [ADR-058](adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md) 이 정했다
- 민감 항목 본문의 완전 삭제
- `always_inject` 칸 제거

Memory 의 기본 근거는 [`adr/ADR-003-memory-권한은-주입으로-강제한다.md`](adr/ADR-003-memory-권한은-주입으로-강제한다.md) 와
[`adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md`](adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md) 에 있다.
층을 나누는 근거는
[`adr/ADR-015-memory-는-층을-나눠-싣는다.md`](adr/ADR-015-memory-는-층을-나눠-싣는다.md) 에 있다.

## 실행 공간 파일

사용자가 「파일 공간」 화면에서 자기 실행 공간 `/workspace` 를 보는 길이다.
결정은 [ADR-20261009 / workspace-explorer](adr/ADR-20261009-workspace-explorer.md), 실행 공간의 모양은 [`hermes/sandbox.md`](hermes/sandbox.md) 가 갖는다.
코드는 `backend` 의 최상위 패키지 `workspace` 에 둔다. 다른 패키지는 이 패키지를 쓰지 않는다.

### 설정

| 키 | 환경 변수 | 비었을 때 |
| --- | --- | --- |
| `assistant.sandbox-workspace.root` | `ASSISTANT_SANDBOX_WORKSPACE_ROOT` | 기동한다. 상태 조회와 관리자 용량은 `available: false` 이고, 그 밖의 경로가 503 `WORKSPACE_UNAVAILABLE` 이다 |
| `assistant.sandbox-workspace.delete-socket` | `ASSISTANT_SANDBOX_WORKSPACE_DELETE_SOCKET` | 기동한다. 지우기가 `WORKSPACE_DELETE_UNAVAILABLE` 이고 상태 조회는 `deletable: false` 다 |

`root` 는 실행 공간 정책의 `workspace_root` 와 같은 디렉터리를 Control Plane 에서 본 경로다. 읽기 전용으로 붙인다.
붙이는 일은 `fos-home-infra` 가 한다. 루트가 링크가 아닌 디렉터리가 아니어도 `WORKSPACE_UNAVAILABLE` 이다.

### 경로 규칙

요청자의 디렉터리는 `<root>/u<사용자 번호>` 하나다. 요청은 주인을 정하지 못한다.
요청의 `path` 는 그 디렉터리 안의 상대 경로이고 `/` 로 조각을 나눈다. 빈 값은 그 디렉터리 자체다.

| 거절하는 것 | 응답 |
| --- | --- |
| 빈 조각, `.`, `..`, NUL, 제어 문자, 맨 앞의 `/` | 400 `VALIDATION_FAILED` |
| 전체 4,096 바이트, 조각 하나 255 바이트, 조각 64개를 넘는다 | 400 `VALIDATION_FAILED` |
| 중간 조각이 디렉터리가 아니거나 심볼릭 링크다 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
| 없는 경로 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |

중간 조각은 링크를 따라가지 않고 연다. glibc 위의 JDK 는 `SecureDirectoryStream` 으로 조각마다 디렉터리 핸들을 열어, 판정한 뒤 다른 것으로 바뀐 경로를 따라가지 않는다.
그 기능이 없으면 대체 경로를 탄다. 운영 이미지(`eclipse-temurin:21-jre-alpine`)는 musl 이라 이쪽이고, 개발 기계(macOS)도 그렇다. 대체 경로는 경고를 한 번 남긴다.
대체 경로는 조각마다 링크인지 본 뒤 경로로 열고, 연 뒤에 다시 본다. 중간 조각이 모두 링크가 아닌 디렉터리인지, 실제 경로가 사용자 디렉터리 아래인지, 마지막 조각을 열기 전에 본 파일과 연 뒤 다시 본 파일이 같은지 확인하고 어긋나면 404 다.
하드 링크 수와 읽기 권한은 두 경로 모두 경로로 다시 본다. 본문은 위 방법으로 연 것만 준다.
하드 링크 수(`unix:nlink`)를 읽지 못하는 파일 시스템이면 1 로 본다.
이 사후 확인은 확인과 열기 사이의 틈을 줄일 뿐 없애지 못한다. glibc 이미지로 옮겨 대체 경로 없이 꺼지게(fail-closed) 하는 일은 이슈 #359 가 갖는다([ADR-20261009 / workspace-explorer](adr/ADR-20261009-workspace-explorer.md) 의 「감당할 것」).

목록의 한 줄은 아래 종류 가운데 하나다.

| `kind` | 무엇 | 본문 |
| --- | --- | --- |
| `DIRECTORY` | 디렉터리 | 목록으로 연다 |
| `FILE` | 일반 파일 | 하드 링크가 하나이고 Control Plane 이 읽을 수 있을 때만 준다 |
| `LINK` | 심볼릭 링크. 가리키는 곳을 읽지 않는다 | 주지 않는다 |
| `OTHER` | FIFO, 소켓, 장치 | 주지 않는다 |

`readable` 이 거짓이면 화면은 「읽을 수 없음」 을 보인다. 권한이 없는 파일, 하드 링크가 둘 이상인 파일, `LINK`, `OTHER` 가 그렇다.
`openable` 은 이름이 주소 조각으로 쓸 수 있는지다. `%`, `;`, `\` 가 든 이름은 Control Plane 의 요청 방화벽이 주소에서 거절하므로 미리보기와 내려받기를 열지 않는다. 목록과 지우기는 `path` 인자로 하므로 그런 이름의 디렉터리도 열고 지운다.
그런 디렉터리 안의 파일은 자기 이름이 괜찮아도 주소가 그 디렉터리 이름을 지나므로, 화면이 미리보기와 내려받기를 열지 않는다.

### API

모든 경로는 웹 토큰의 사용자로 판정한다. 웹 서버 라우트는 같은 경로를 `/api/workspace/...` 로 옮긴다.

| 경로 | 하는 일 | 응답 |
| --- | --- | --- |
| `GET /api/v1/workspace` | 공간의 상태 | `{available, deletable, exists, runningExecutions, agents: [{code, name, shared}]}`. `exists` 는 사용자 디렉터리가 있는지다. `agents` 는 요청자가 주인이고 켜져 있으며 지우지 않은 에이전트이고, `shared` 는 그룹에 공개했는지다. `runningExecutions` 는 사용자 실행 한도가 세는 지금 쥔 자리 수다 |
| `GET /api/v1/workspace/entries?path=` | 디렉터리 하나의 목록 | `{path, entries: [{name, kind, size, modifiedAt, readable, openable}], truncated}`. 디렉터리를 읽는 순서로 1,001개까지 읽고, 그 가운데 1,000개를 디렉터리 먼저, 이름 순서로 준다. 1,001번째가 있으면 `truncated` 가 참이고, 그때는 순서상 앞선 항목도 빠질 수 있다. `size` 는 `FILE` 만 채운다. 사용자 디렉터리가 아직 없으면 빈 목록이다 |
| `GET /api/v1/workspace/files/{경로}` | 미리보기 본문 | 아래 「본문 머리글」. 경로의 조각마다 URL 인코딩한다 |
| `GET /api/v1/workspace/files/{경로}?download=1` | 내려받기 | 크기 상한 없이 스트림으로 준다 |
| `DELETE /api/v1/workspace/entries?path=` | 지우기 | 아래 「지우기」 |
| `GET /api/v1/admin/workspaces` | 실행 공간별 용량(사용자, 주인 없는 에이전트). `ADMIN` 만 | 아래 「관리자 용량」 |

본문 경로의 오류는 아래와 같다. 루트 확인(503), 경로 검사(400) 다음에, 미리보기는 확장자(415), 크기(413), 종류(404), 읽기 권한(403) 차례로 판정한다. 내려받기는 종류와 읽기 권한만 본다.

| 판정 | 응답 |
| --- | --- |
| `FILE` 이 아니다 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
| 읽을 수 없다(권한, 하드 링크) | 403 `WORKSPACE_ENTRY_UNREADABLE` |
| 미리보기를 정하지 않은 확장자 | 415 `WORKSPACE_PREVIEW_UNSUPPORTED` |
| 미리보기 크기를 넘는다 | 413 `WORKSPACE_PREVIEW_TOO_LARGE` |

목록에서 Control Plane 이 읽지 못하는 디렉터리는 403 `WORKSPACE_ENTRY_UNREADABLE` 이다. 예상하지 못한 입출력 오류는 500 `INTERNAL_ERROR` 이고 로그에는 사용자 번호와 예외 종류만 남는다.
응답 본문은 파일을 연 시점의 크기까지만 보낸다. 그 사이 파일이 커져도 `Content-Length` 와 본문이 어긋나지 않는다.

### 본문 머리글

미리보기는 확장자로 형식을 정한다. 파일의 내용으로 형식을 짐작하지 않는다.

| 확장자 | `Content-Type` | 크기 상한 |
| --- | --- | --- |
| `html`, `htm` | `text/html; charset=utf-8` | 5 MiB |
| `png`, `jpg`, `jpeg`, `gif`, `webp` | 그 사진 형식 | 20 MiB |
| `css` | `text/css; charset=utf-8`. HTML 미리보기가 상대 경로로 부르는 스타일이 적용되게 한다 | 1 MiB |
| `csv`, `tsv` 와 아래 글 확장자, 확장자가 없는 이름 | `text/plain; charset=utf-8` | 1 MiB |

글 확장자는 `txt`, `md`, `markdown`, `log`, `json`, `jsonl`, `yaml`, `yml`, `toml`, `ini`, `cfg`, `conf`, `env`, `py`, `js`, `mjs`, `cjs`, `ts`, `tsx`, `jsx`, `java`, `kt`, `go`, `rs`, `rb`, `sh`, `bash`, `zsh`, `sql`, `xml`, `svg`, `scss` 다.
SVG 는 스크립트를 품을 수 있어 사진이 아니라 글로 보인다.
확장자는 이름의 마지막 `.` 뒤를 소문자로 읽는다. `.` 으로 시작하고 다른 `.` 이 없는 이름은 그 뒤 전체가 확장자다. `.env` 는 `env` 라 글이고, `.gitignore` 는 `gitignore` 라 미리보기가 없다. `.` 이 없는 이름(`Makefile`)은 확장자가 없는 이름이다.

| 머리글 | 미리보기 | 내려받기 |
| --- | --- | --- |
| `Content-Type` | 위 표 | `application/octet-stream` |
| `Content-Disposition` | `inline; filename="<ASCII 로 옮긴 이름>"; filename*=UTF-8''<이름>` | `attachment; filename="<ASCII 로 옮긴 이름>"; filename*=UTF-8''<이름>` |
| `Content-Security-Policy` | HTML 은 결과물과 같은 값([`backend/artifact.md`](backend/artifact.md) 의 「경로」). 그 밖은 `sandbox; default-src 'none'` | `sandbox; default-src 'none'` |
| `X-Content-Type-Options` | `nosniff` | `nosniff` |
| `Cache-Control` | `private, no-store` | `private, no-store` |

`Content-Disposition` 은 Spring 의 `ContentDisposition` 에 이름과 UTF-8 을 주어 만든다. ASCII 로 옮긴 이름과 `"`, `\` 의 escape 는 Spring 이 정한다.
web 서버 라우트는 이 다섯 머리글과 `Content-Length` 만 옮긴다.
HTML 이 상대 경로로 부르는 CSS 와 사진은 같은 `files/` 아래 주소라 주인 확인 뒤에 받는다.

### 로그와 기록

본문은 로그와 실행 기록에 남기지 않는다. 도우미를 부른 지우기마다 사용자 번호, 상대 경로, 종류, 지운 항목 수, 결과를 `INFO` 로그 한 줄로 남긴다. 실패하면 일부가 지워졌을 수 있어 지운 항목 수를 `-` 로 적는다. 경로의 제어 문자는 `\uXXXX` 로 바꿔 로그 줄을 끊지 못하게 한다. 도우미를 부르기 전에 끝난 요청은 남기지 않는다.
목록과 본문의 오류 로그는 사용자 번호와 오류 종류만 남기고 경로를 남기지 않는다.

### 지우기

**지우기는 경로 하나를 받는다.** 빈 경로(사용자 디렉터리 자체)는 400 `VALIDATION_FAILED` 다.
Control Plane 은 경로 규칙을 먼저 검사하고 읽기 마운트에서 그 경로가 있는지 본 뒤 운영의 권한 도우미를 부른다.

| 판정 | 응답 |
| --- | --- |
| 지운다 | 200 `{kind, entries, bytes}`. `entries` 는 지운 항목 수, `bytes` 는 지운 일반 파일의 크기 합이다 |
| 도우미 socket 이 설정되지 않았다 | 503 `WORKSPACE_DELETE_UNAVAILABLE` |
| 없는 경로, 링크를 지나는 경로 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
| 상위 디렉터리를 Control Plane 이 읽지 못한다 | 403 `WORKSPACE_ENTRY_UNREADABLE`. 도우미를 부르지 않는다 |
| 디렉터리 안의 항목이 10,000 개를 넘는다 | 409 `WORKSPACE_DELETE_TOO_MANY`. 아무것도 지우지 않는다 |
| 도우미가 실패했거나 30초 안에 답하지 않았다 | 502 `WORKSPACE_DELETE_FAILED` |

루트 확인(503 `WORKSPACE_UNAVAILABLE`)과 예상하지 못한 입출력 오류(500)는 위 「API」 절과 같다.

**권한 도우미와의 계약.** 도우미는 `fos-home-infra` 가 만들고 운영한다. 이 저장소는 아래 계약만 갖는다.

- socket 은 unix stream socket 이다. Control Plane 만 열 수 있게 둔다. 망에 열지 않는다
- 요청 하나에 연결 하나다. Control Plane 이 UTF-8 JSON 한 줄을 `\n` 으로 끝내 보내고, 도우미가 JSON 한 줄로 답한 뒤 연결을 닫는다
- 답 한 줄은 64 KiB 를 넘지 않는다. 넘거나 JSON 이 아니면 Control Plane 은 502 `WORKSPACE_DELETE_FAILED` 로 답한다
- 요청은 `{"version": 1, "owner": "u12", "path": "reports/a.csv", "max_entries": 10000}` 이다
- 성공 답은 `{"ok": true, "kind": "FILE", "entries": 1, "bytes": 2048}` 이다. `kind` 는 목록의 `kind` 와 같은 네 값이다
- 실패 답은 `{"ok": false, "code": "<코드>"}` 이다. 코드는 아래 표의 다섯이다

| 코드 | 뜻 | Control Plane 응답 |
| --- | --- | --- |
| `INVALID_REQUEST` | 주인 키나 경로가 규칙에 맞지 않는다 | 502 `WORKSPACE_DELETE_FAILED` |
| `NOT_FOUND` | 없는 경로 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
| `LINK_IN_PATH` | 중간 조각이 링크이거나 디렉터리가 아니다 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
| `TOO_MANY_ENTRIES` | 디렉터리 안의 항목이 `max_entries` 를 넘는다. 아무것도 지우지 않았다 | 409 `WORKSPACE_DELETE_TOO_MANY` |
| `FAILED` | 지우다 실패했다. 일부가 지워졌을 수 있다 | 502 `WORKSPACE_DELETE_FAILED` |

도우미가 지킬 것은 아래와 같다.

- 주인 키는 `^[a-z][a-z0-9-]{0,63}$` 이고, 지우는 범위는 `<workspace_root>/<주인 키>` 아래뿐이다. 주인 디렉터리 자체는 지우지 않는다
- 경로 규칙은 위 「경로 규칙」 과 같다
- 주인 디렉터리부터 조각마다 링크를 따라가지 않고 디렉터리 핸들로 연다. 마지막 조각이 링크면 링크만 지운다
- 디렉터리는 먼저 안의 항목을 링크를 따라가지 않고 센다. `max_entries` 를 넘으면 지우지 않고 답한다. 넘지 않으면 안쪽부터 지운다
- 지우는 크기에는 상한을 두지 않는다. 지우는 비용은 항목 수를 따르고 파일 크기를 따르지 않는다
- 요청마다 시각, 주인 키, 경로, 종류, 지운 항목 수, 결과를 감사 기록 한 줄로 남긴다. 파일 본문은 읽지도 남기지도 않는다
- 실행 공간 루트만 쓰기로 붙이고, 지우는 데 필요한 권한만 갖는다

### 관리자 용량

`GET /api/v1/admin/workspaces` 는 `{available, spaces: [{kind, id, name, bytes, entries, partial}]}` 를 준다.
`kind` 는 `USER`(디렉터리 `u<번호>`) 나 `AGENT`(디렉터리 `a<번호>`) 이고 `name` 은 사용자 이름이나 에이전트 이름이다. 이름을 찾지 못하면 `null` 이다.
번호는 0 으로 시작하지 않는다(`u01` 은 세지 않는다). 그 밖의 이름을 가진 디렉터리는 세지 않는다. 파일 이름과 경로는 응답에 없다.

요청할 때 링크를 따라가지 않고 센다. `bytes` 는 일반 파일 크기의 합이다.
공간 하나에 항목 200,000 개, 요청 전체에 30초를 넘기면 거기서 멈추고 `partial` 을 참으로 둔다. 시간은 항목 사이에서 확인하므로 디렉터리 하나를 여는 데 걸린 시간만큼은 넘길 수 있다. 읽지 못한 디렉터리는 항목으로 세고 `partial` 을 참으로 둔다.
공간은 `USER`, `AGENT` 순서와 번호 순서로 센다. 30초가 지난 뒤의 공간은 세지 않고 `bytes` 와 `entries` 를 0, `partial` 을 참으로 두어 응답에 넣는다.
줄은 `bytes` 가 큰 순서다.

루트가 설정되지 않았거나 링크가 아닌 디렉터리가 아니면 200 과 `available` 거짓, 빈 `spaces` 다. 루트 바로 아래를 읽지 못하면 500 `INTERNAL_ERROR` 이고 로그에는 예외 종류만 남는다.

## 화면을 검증하는 방법

테스트는 확인하는 대상을 나눠 둔다.

| 위치 | 확인하는 것 | 띄우는 것 |
| --- | --- | --- |
| `test/e2e/` | Control Plane 의 응답과 권한과 기록 | 가짜 Hermes, 백엔드, 데이터베이스 |
| `test/browser/` | 화면의 배치와 동작 | 위에 더해 웹과 Chromium |
| `test/unit/` | 화면이 쓰는 순수 함수 | 없음 |

브라우저 테스트는 `mobile` 과 `desktop` 두 폭에서 돈다. 폭의 값은 `test/browser/playwright.config.ts` 가 갖는다.

**운영 코드에 시험용 문을 만들지 않는다.**
로그인은 테스트가 NextAuth 세션 쿠키를 직접 만들어 넣는다.
테스트일 때만 켜지는 우회를 두면 그 문이 운영에도 남는다.

## 비밀값을 두는 곳

| 값 | 두는 곳 |
| --- | --- |
| 사용자의 AI credential | Hermes 쪽. provider API key 는 profile `.env` 에 둔다. OAuth 로그인은 profile 에 자기 `auth.json` 이 없으면 여러 profile 이 Hermes 루트의 것을 함께 쓴다([`hermes/README.md`](hermes/README.md)) |
| profile 의 API server key | 홈서버의 mode 600 파일. 파일 이름이 profile 이름이다 |
| Hermes 대시보드를 부를 토큰 | Control Plane 의 환경 변수와 그 plugin 의 환경 변수 |
| 웹과 Control Plane 이 나눠 가지는 HMAC 비밀값 | 두 서비스의 환경 변수 |
| 사용자 본문을 감싸는 KEK | 홈서버 파일. 데이터베이스 백업과 다른 자리에 둔다([ADR-20261008 / data-encryption](adr/ADR-20261008-data-encryption.md)) |
| 사용자별 데이터 key | `user_data_key` 에 KEK 로 감싼 채로 둔다. 푼 key 는 Control Plane 메모리에만 있다 |

데이터베이스에는 어떤 비밀값도 원문으로 넣지 않는다.
`agent` 는 profile 이름과 주소만 적는다.
profile key 와 AI credential 은 계속 홈서버 파일에 둔다.

## 아직 만들지 않은 것

- Hermes 안의 `delegate_task` 하위 에이전트가 자기 실행 줄을 남기는 경로.
  그 하위 에이전트는 Hermes 안에서만 돌고 사건으로만 보인다.
  우리 실행 줄이 생기는 자식은 `agent_delegate`, 흐름의 하위 실행, Memory 제안이다.
  사용량과 금액은 실행 줄 없이 `subagent_usage_job` 줄에 남겨 합계에 더한다([ADR-062](adr/ADR-062-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md)).
  그 자식의 provider 는 대시보드 plugin 의 읽기 경로로 받는다([ADR-067](adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md))
- `agent_stop` 이 그 실행 아래의 실행까지 멈추는 것. 지금은 그 실행만 멈춘다
- 사용자가 turn 을 중지할 때 Hermes `delegate_task` 하위 에이전트를 실제로 멈추는 것.
  지금은 origin 실행이나 그 루트 실행이 `CANCELLED` 인 하위 에이전트의 Control Plane MCP 호출만 거절한다([ADR-037](adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)).
  루트와 origin 사이의 중간 실행만 중지된 경우는 보지 않는다.
  멈출 수 있는 길은 profile 플러그인 쪽에 있고, 부모 run 이 끝난 뒤의 자식은 그 길로도 멈추지 못한다([`hermes/delegation.md`](hermes/delegation.md#native-하위-에이전트를-멈추는-길))
- 여러 Control Plane 이 함께 세는 사용자 실행 한도. 지금은 한 프로세스 안의 사용자 잠금으로 세고 만든다([`backend/execution-limit.md`](backend/execution-limit.md) 의 「서버 한 대 전제」)
- Hermes native 하위 에이전트와 cron 을 사용자 실행 한도에 넣는 것. Control Plane 이 제출하지 않아 세지 못한다
- 스킬 zip 묶음의 받기와 미리보기, 올리기와 그 화면. 지금은 저장 기반(넓힌 경로, scripts 와 실행 공간, 이전 버전)만 있다([ADR-20261009 / skill-package](adr/ADR-20261009-skill-package.md))
- `connector_action` 줄의 보관 기한과 정리. 지금은 도구 호출마다 남긴 줄을 지우지 않는다
- 커넥터 연결에 다시 인증이 필요하다는 알림. 연결 상태에 재인증 상태가 없고, 토큰이 거절된 것을 연결 상태로 옮기는 지점도 없다. 그 상태를 정한 뒤 알림 종류를 더한다([`backend/notification.md`](backend/notification.md))
- 예약 작업의 실패 다시 하기와 일시 정지, 도구 미리 허락, 작업 제안. 목록과 넣지 않기로 한 것은 [`backend/task.md`](backend/task.md) 의 「다음 단계」 가 갖는다
- 알림을 웹 밖으로 보내는 채널. 첫 채널은 브라우저 웹 푸시로 정했다. 지금은 웹 안의 알림 단추와 목록뿐이다
- 먼저 살펴보기의 목표별 변화 판정. 외부 변화를 Control Plane 이 알지 못해 `NO_CHANGE` 로 모델을 건너뛰지 않는다. 남은 것은 [ADR-080](adr/ADR-080-먼저-살펴보기는-점검-대화의-turn-하나로-돌고-읽기-경계를-control-plane-이-강제한다.md) 의 「다음 단계」 가 갖는다
- 커넥터 plugin 이 일반 에이전트용 `proactive-check` 스킬을 선언하는 manifest 칸. 지금은 그 스킬을 에이전트에 따로 둔다
