# 비서가 만든 이미지 기술 설계

스펙 [`spec.md`](spec.md) 의 PR1(REQ1 부터 REQ10 까지)과 PR2(REQ11 부터 REQ14 까지), PR3(REQ15 부터 REQ17 까지), PR4(REQ18, REQ19)를 설계한다. PR2 의 설계는 「PR2: 기본 비서에 켜기와 자동 설정, 사람이 없는 실행」 절이, PR3 의 설계는 「PR3: 지금 대화의 사진과 그림을 바탕으로 고치기」 절이, PR4 의 설계는 「PR4: 만든 그림을 블로그 원고에 넣기」 절이 갖는다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | fos-ctx 가 `image_generate` 의 결과에서 그림 파일을 읽어 Control Plane 에 올리고, 모델에게 가는 결과에서 경로를 뺀다. Control Plane 은 그림을 대화의 결과물 폴더에 쓰고 `chat_artifact` 행을 만든 뒤 turn 이 끝날 때 답에 묶는다. 화면은 그림 행을 답 글 아래에 `<img>` 로 그린다 |
| REQ2 | 행은 만든 순서대로 번호가 붙고 응답도 그 순서다. 화면은 받은 순서로 나란히 그린다 |
| REQ3 | 화면의 크게 보기 대화 상자가 기존 결과물 파일 주소를 그대로 쓴다. 「내려받기」 는 같은 주소에 `download` 이름으로 제목을 준다 |
| REQ4 | Control Plane 이 매 turn 실행 입력 앞에 붙이는 결과물 폴더 단락에 「그림 만들기」 규칙을 더한다 |
| REQ5 | 지시문은 올릴 때 `chat_artifact` 의 새 칸에 암호화해 두고 메시지 응답의 결과물 항목으로 내보낸다. 화면이 접힌 칸으로 그린다 |
| REQ6 | 모델이 지시문 첫 줄에 적은 `제목:` 줄을 fos-ctx 가 떼어 Control Plane 에 보낸다. Control Plane 이 제목을 파일 이름으로 쓰고, 같은 이름이 있으면 뒤에 번호를 붙인다. 제목이 없으면 만든 날짜와 시각이다 |
| REQ7 | 답에 묶인 그림 행은 기존 자동 보관이 HTML 과 같은 길로 보관한다. 화면은 보관 상태를 그림 아래에 그리고, 보관본이 없으면 크게 보기에 「보관」 단추를 보인다 |
| REQ8 | 보관함 목록이 그림 항목 줄에 작은 그림을 그린다. 이름은 파일 이름에서 확장자를 뗀 제목이다 |
| REQ9 | 메시지 응답이 지워진 그림 행마다 그 사용자의 살아 있는 보관본 번호를 함께 준다. 화면이 안내와 「보관함에서 보기」 링크를 그린다 |
| REQ10 | 파일은 기존 결과물 경로가 대화 주인에게만 준다. 이미지 도구가 실패하면 Hermes 의 오류 결과가 그대로 모델에게 가고, 올리기가 실패하면 fos-ctx 가 경로 없는 오류 결과로 바꾼다 |
| REQ11 | 기본 도구 설정에 `image_gen` 을 더해 새 기본 비서에 켠다. 이미 있는 기본 비서는 마이그레이션이 `agent.pending_default_toolsets` 에 한 번 넣고 `AgentPendingToolsets` 가 요청 밖에서 켠다. 등급은 관리자 등급 그대로다 |
| REQ12 | Control Plane 이 `image_gen` 이 든 도구 저장에 `ensure_image_service: true` 를 넣어 보내고, 대시보드 plugin 이 같은 쓰기에서 profile 설정의 `image_gen.provider` 를 넣는다. 쓰지 못하면 저장 전체가 실패한다 |
| REQ13 | 켜지 못한 기본 비서는 기다리는 목록에 남고 `AgentPendingToolsets` 가 5분마다 다시 켠다. 실행 공간 정책 등록은 Control Plane 이 하지 못해 관리자 알림으로 남긴다. 사람의 도구 저장이 그 도구의 켜짐을 바꾸면 목록에서 뺀다 |
| REQ14 | fos-ctx 가 이미 묻는 도구 판정(`/internal/hermes/tool-guard`)이 그 호출의 실행을 찾아 트리 루트의 「사람이 없는 실행」 표시가 참이면 `image_generate` 를 막는다. fos-ctx 는 바꾸지 않는다 |
| REQ15 | 모델이 `image_url` 과 `reference_image_urls` 에 `attachment:<첨부 번호>` 나 `generated:<제목>` 을 적는다. fos-ctx 의 `pre_tool_call` 이 하나씩 Control Plane 에서 받아 profile 의 media cache 에 사본으로 쓰고 인자를 그 경로로 바꾼다. 원본은 읽기만 한다 |
| REQ16 | Control Plane 이 origin 실행의 사용자와 대화에서만 찾는다. 요청자가 보낸 그 대화의 사진과 그 대화의 그림 행만 내준다. 하나라도 받지 못하거나 가리킴이 아닌 값(경로, 주소, data URL)이 있으면 fos-ctx 가 호출을 막고 어느 것이 왜 안 되는지 적은 글을 모델에게 준다 |
| REQ17 | 실행 입력의 그림 규칙에 고치기 규칙과 그 대화의 그림 제목 목록을 더한다 |
| REQ18 | 모델이 `content_draft_image` 에 그림 제목을 주면 Control Plane 이 그 그림의 사진 첨부 사본을 같은 대화에 만들고 첨부 번호와 파일 이름을 돌려준다. 모델은 그 값을 원고의 사진 자리에 쓰고, 미리보기와 원고 저장, 네이버 임시저장은 사진과 같은 길로 그 사본을 쓴다 |
| REQ19 | 그림 파일이 보관 기간 정리로 지워졌으면 사본을 만들지 않고 `IMAGE_SOURCE_GONE` 과 고정 안내를 돌려준다. 이미 만든 사본은 사진 첨부라 사진의 보관 기간과 삭제 규칙을 그대로 따른다 |

## 책임과 경계

| 모듈 | 맡는 것 |
| --- | --- |
| `hermes/plugins/fos-ctx` | `image_generate` 호출에서 제목 줄을 떼고, 성공한 결과의 그림 파일을 Control Plane 에 올리고, 모델에게 갈 결과를 바꾼다. 누구의 어느 대화인지는 정하지 않는다 |
| `backend` 의 `mcp` | 올리기 요청의 토큰과 서명을 확인하고 origin 실행에서 요청자와 대화를 정한다. 저장은 `chat` 에 넘긴다 |
| `backend` 의 `chat.artifact` | 그림 형식과 크기 검사, 이름 정하기, 파일 쓰기, 행 만들기, 답에 묶기, 지시문 암호화, 보관본 찾기 |
| `web` | 답 안의 그림, 크게 보기, 지시문 접힌 칸, 보관 상태와 단추, 보관함의 작은 그림 |

**그림을 옮기는 주체는 fos-ctx 다.** `image_generate` 는 Hermes 프로세스 안에서 돌고 그림을 그 profile 의 캐시 폴더에 쓴다. Control Plane 은 그 폴더를 읽지 못하고, `/v1/runs` 는 `MEDIA:` 를 그림으로 바꾸지 않는다([`hermes-contract.md`](../../../hermes/docs/hermes-contract.md) 의 「같은 프로세스에 남는 구멍」). 운영 테스트에서 모델이 `artifact_write` 로 옮기려다 실패한 까닭은 그 도구가 그림을 HTTPS 주소로만 받고 바이트를 받지 않아서다.
근거와 기각한 대안은 [ADR-20261119 / generated-image](../../adr/ADR-20261119-generated-image.md) 가 갖는다.

**Hermes core 는 고치지 않는다.** fos-ctx 가 이미 쓰는 `pre_tool_call` 의 인자 병합과 `transform_tool_result` 만 쓰고 Hermes 의 이름을 새로 import 하지 않는다.
대신 Hermes 의 동작 셋에 새로 기댄다. `image_generate` 결과의 `success` 값과 `image` 값, 그림을 쓰는 캐시 폴더, 내장 도구에도 `modify` 의 `args` 가 원래 인자를 덮는다는 것이다. 이 셋을 [`hermes-contract.md`](../../../hermes/docs/hermes-contract.md) 에 적고 `hermes/tests` 의 계약 검사에 넣어, Hermes 를 올릴 때 다시 확인하게 한다.

**요청 본문이 사용자나 대화를 정하지 못한다.** 올리기 요청에는 대화 식별자가 없다. Control Plane 이 서명한 session 에서 origin 실행을 찾고 그 실행의 사용자와 대화를 쓴다. 다른 에이전트에게 맡긴 실행과 Hermes 내장 위임의 자식도 같은 길로 부모 대화에 닿는다.

## 인터페이스와 데이터

### fos-ctx 의 hook

| 때 | 하는 일 |
| --- | --- |
| `pre_tool_call`, 도구가 `image_generate` | 기존 도구 판정을 그대로 거친다. 통과하면 `prompt` 의 첫 줄이 `제목:` 으로 시작할 때 그 줄을 떼고 `{"action": "modify", "args": {"prompt": <나머지>}}` 를 돌려준다. 뗀 제목은 `(session_id, tool_call_id)` 로 프로세스 메모리에 둔다. 항목 수에 상한을 두고 결과 hook 이 꺼내면 지운다 |
| `transform_tool_result`, 도구가 `image_generate` | 결과가 `success` 이고 `image`(실행 공간이 있어 `host_image` 가 오면 그 값)가 그 profile 의 이미지 캐시 폴더(Hermes 홈의 `cache/generated/images`) 아래 일반 파일이면 읽어 올린다. 링크를 푼 실제 경로로 본다. 지시문은 hook 이 받은 `args` 의 `prompt` 에서 읽고, 거기에 `제목:` 줄이 남아 있으면 그 줄을 제목으로 쓴다. 그 밖의 결과(실패, 원격 주소, 캐시 폴더 밖 경로)는 건드리지 않는다. `tool_call_id` 가 비면 올리지 않고 아래 오류 결과로 바꾼다 |

올린 뒤 모델이 받는 결과는 아래 둘 가운데 하나다. 어느 쪽에도 경로가 없다.

```json
{"success": true, "saved": true, "title": "수채화 고양이", "message": "그림을 대화에 저장했다. 사용자의 화면에 자동으로 보인다. 답에 파일 경로나 MEDIA: 를 쓰지 않는다."}
```

```json
{"success": false, "error": "그림을 만들었지만 대화에 저장하지 못했다. 사용자에게 그림을 보여 주지 못했다고 알리고 잠시 뒤 다시 부탁하라고 안내한다."}
```

올리기에 성공하면 캐시의 원본 파일을 지운다. 지우지 못해도 결과는 같다.
제목은 200자, 지시문은 30,000자로 잘라 보낸다. Control Plane 이 받는 길이(500자, 32,000자)를 넘기면 그림까지 거절되기 때문이다.
올리기의 HTTP 제한 시간은 15초다. Hermes 의 callback 제한(기본 30초)보다 짧게 두어, 늦어지면 hook 안에서 오류 결과로 끝낸다. callback 제한을 넘기면 Hermes 가 그 profile 의 hook 을 60초 억제한다.
올리기 경로가 없는 옛 Control Plane(404)이면 결과를 바꾸지 않는다. 플러그인을 먼저 배포해도 지금과 같은 동작이 남는다(rule-66).

### 올리기 경로

`POST /internal/hermes/generated-image`. 인증은 `/mcp` 와 같은 profile 토큰이다.
본문은 `multipart/form-data` 이고 조각은 둘이다. 그림을 JSON 안의 base64 로 보내지 않는다. JSON 문자열 상한에 걸리고 메모리를 두 배로 쓴다.

| 조각 | 내용 |
| --- | --- |
| `meta` | JSON `{"v": 1, "root_session_id", "session_id", "tool_call_id", "title", "prompt", "sig"}` |
| `image` | 그림 바이트 그대로 |

서명할 글은 `v1-generated-image`, 루트 session, session, `tool_call_id`, 그림 바이트의 SHA-256, 제목의 SHA-256, 지시문의 SHA-256 을 `\n` 으로 이은 UTF-8 이다. key 는 `_fos_ctx` 와 같다. 제목이 없으면 빈 글의 해시다.

| 판정 | 응답 |
| --- | --- |
| 토큰이나 서명이 맞지 않거나, origin 실행을 찾지 못하거나, origin 실행이나 그 트리의 루트가 중지됐거나, 대화 없는 실행이다 | `MCP_CALL_CONTEXT_INVALID` |
| 그림이 PNG, JPEG, WebP 가 아니거나 상한을 넘는다 | `VALIDATION_FAILED` |
| 같은 호출의 같은 그림이 다시 왔다 | 처음 저장한 결과를 그대로 돌려준다 |
| 저장했다 | 200 `{"title": "<정한 제목>"}` |
| 저장하다 실패했다 | 500 `INTERNAL_ERROR` |

**이 경로는 404 를 내지 않는다.** fos-ctx 가 404 를 이 경로가 없는 옛 Control Plane 으로 읽고 결과를 바꾸지 않기 때문이다. 저장 중의 `*_NOT_FOUND` 예외는 500 으로 바꿔 답한다. 도구 판정 경로와 같은 규칙이다.

형식은 확장자가 아니라 파일 머리 바이트로 정한다. 상한은 `assistant.artifact.generated-image-max-bytes`(기본 20MiB)다. 보관함의 파일 하나 상한과는 별개다(스펙 DECISION20). 보관함의 상한은 [ADR-20261119 / file-space-put](../../adr/ADR-20261119-file-space-put.md) 이 25MiB 로 올렸다.

### 저장

- 파일은 대화 결과물 폴더의 `generated/<제목>.<확장자>` 다
- 제목은 글자와 숫자, 공백, `-`, `_`, `(`, `)`, `,` 만 남긴다. 주소에서 방화벽이 거절하는 `%` 와 `;`, 경로 글자, 제어 문자가 빠진다. 앞뒤의 공백과 점을 떼고 code point 60개로 자른다. 남는 것이 없으면 만든 날짜와 시각이다
- 겹침은 그 대화의 그림 행(지워진 것 포함)에서 확장자를 뗀 이름으로 본다. 행이 없어도 그 경로에 파일이 이미 있으면 겹친 것으로 본다. 에이전트가 파일 도구로 써 둔 파일을 덮어쓰지 않는다. 같은 이름이 있으면 `<제목> 2`, `<제목> 3` 을 차례로 본다. 보관 기간 정리 뒤에도 경로를 다시 쓰지 않으므로 지워진 그림의 보관본을 경로로 찾을 수 있다. 이름 정하기부터 행 만들기까지 대화마다 한 번에 하나만 돈다
- `chat_artifact` 에 행을 만든다. `message_id` 는 비워 두고 turn 이 끝날 때 채운다. 새 칸은 지시문 암호문 `generation_prompt`, 그 key 번호 `generation_prompt_key_id`, 되풀이 요청을 한 건으로 만드는 `generation_call_key`(session, `tool_call_id`, 그림 SHA-256 을 묶은 SHA-256, 유일) 셋이다. 새 표는 만들지 않는다. 칸의 정의는 마이그레이션과 [`data-schema.md`](../../../backend/docs/data-schema.md) 가 갖는다
- 지시문은 대화 주인의 데이터 key 로 암호화한다([ADR-20261008 / data-encryption](../../../backend/docs/adr/ADR-20261008-data-encryption.md)). 암호화를 쓸 수 없는 설치는 메시지 본문과 같이 평문으로 둔다
- 그림 행인지는 `generation_call_key` 가 있는지로 가린다. HTML 이 부르는 사진은 지금처럼 행이 없다

### 답에 묶기와 보관

`ArtifactService.recordTurn` 이 HTML 을 묶는 자리에서, 그 대화에서 `message_id` 가 비었고 보관 사본이 아니며 지워지지 않은 그림 행 모두에 답 메시지 번호를 적는다. turn 시작 시각으로 자르지 않는다.

- 다른 에이전트에게 맡긴 실행은 부모 turn 이 끝난 뒤에 돌 수 있다([`agent-skill.md`](../agent-skill.md) 의 「다른 에이전트에게 맡기기」). 그 그림은 결과를 전하는 다음 turn 의 답에 묶인다. Hermes 내장 위임의 자식도 부모 turn 뒤에 돌 수 있어 같다. 부모 turn 안에서 끝난 자식의 그림은 그 답에 묶인다
- 답 메시지가 없는 turn 의 그림은 그 대화의 다음 답에 묶인다. 한도를 쓰고 만든 그림이 보이지 않은 채 사라지지 않는다
자동 보관은 고치지 않는다. 답에 묶인 행을 차례에 두는 기존 쿼리가 그림 행도 잡고, 보관 서비스는 사진 한 장을 이미 받는다.

### 메시지 응답

`ChatDtos.ArtifactView` 에 세 칸을 더한다. 옛 화면은 모르는 칸을 무시한다.

| 칸 | 뜻 |
| --- | --- |
| `kind` | `IMAGE` 또는 `HTML` |
| `prompt` | 그림 지시문. 그림이 아니거나 없으면 null |
| `vaultItemId` | 그 그림의 살아 있는 보관본 번호. 없으면 null. 같은 대화와 경로의 보관 사본 가운데 가장 새것이다. 지워진 그림의 링크와, 파일이 있는 그림에서 「보관」 단추를 보일지에 쓴다 |

### 실행 입력의 단락

`ArtifactService.agentPreamble` 의 결과물 폴더 단락에 아래 뜻의 줄을 더하고, 그림을 `source_url` 로 저장하라는 기존 줄은 「HTML 이 부를 사진」 으로 좁힌다.

- 아래 줄은 `image_generate` 도구가 있고 사용자가 그림을 부탁했을 때만 따른다고 먼저 적는다. 조건 없는 지시는 싼 모델이 요청과 상관없이 따른다
- `image_generate` 로 만든 그림은 대화에 자동으로 저장되고 보인다. `artifact_write` 나 파일 도구로 옮기지 않고 답에 경로나 `MEDIA:` 를 쓰지 않는다
- 지시문은 사용자의 부탁을 그대로 넘기지 않고 그릴 대상, 구도, 화풍, 조명, 지킬 것을 담아 자세히 쓴다
- 지시문의 첫 줄에 `제목: <짧은 한국어 제목>` 을 적는다
- 만들지 못했으면 그림 없이 까닭과 다시 부탁하는 방법을 답한다

### 답 글의 경로 가리기

Hermes 는 모델에게 그림의 호스트 경로를 돌려주고 모델은 그것을 `MEDIA:<경로>` 나 경로 그대로 답에 적는다. 그 경로는 홈서버의 디렉터리 구조다.
fos-ctx 가 결과를 바꾸기 전에 저장된 답과 hook 이 건너뛰어진 답에는 그 글자가 남는다.

- Control Plane 은 메시지 응답과 보내기 응답에서 답 글의 `MEDIA:` 태그와 Hermes 이미지 캐시 폴더의 경로를 뺀다. `MediaPathMask` 가 맡는다. 저장된 글은 고치지 않는다([ADR-027](../../adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md))
- 화면은 흐르는 답을 그릴 때 같은 규칙으로 뺀다. 답이 흐르는 동안의 글은 Control Plane 의 사건을 조각으로 받아 서버에서 한 번에 가릴 수 없다
- 옛 답의 그림은 Hermes 의 캐시에만 있어 다시 보이게 하지 않는다. 경로 글자만 사라진다
- 태그는 낱말의 일부(`SOCIAL_MEDIA:`)가 아니고 뒤가 경로이거나 비었을 때만 잡는다. 캐시 경로는 웹 주소의 일부가 아닐 때만 잡는다. 공백이 든 폴더 이름의 경로는 공백 앞까지만 잡는다
- 예약 작업의 결과 글과 알림 줄처럼 답 메시지가 아닌 자리는 이 PR 이 가리지 않는다. fos-ctx 가 결과에서 경로를 빼므로 새 실행에는 경로가 생기지 않는다

### 화면

- `message-bubble` 의 결과물 목록을 종류로 나눈다. `HTML` 은 지금의 파일 줄이고 `IMAGE` 는 새 `GeneratedImages` 컴포넌트가 답 글 아래에 그린다
- 그림은 기존 결과물 파일 주소(`/api/chat/conversations/{공개 식별자}/files/{경로}`)를 쓴다. 누르면 기존 `Dialog` 로 화면 가득 크게 보이고, 제목과 「내려받기」, 지시문, 보관 상태와 「보관」 단추가 있다
- 지워진 그림은 안내 글과, `vaultItemId` 가 있으면 `/files?tab=vault&item=<번호>` 링크를 그린다. 보관함 목록은 `item` 이 있으면 그 줄의 미리보기를 펼친다
- 보관함 목록의 그림 줄은 이름 앞에 작은 그림을 늦게 불러(`loading="lazy"`) 그린다. 작은 그림 전용 사본은 만들지 않는다

## PR2: 기본 비서에 켜기와 자동 설정, 사람이 없는 실행

근거와 기각한 대안은 [ADR-20261119 / image-gen-defaults](../../adr/ADR-20261119-image-gen-defaults.md) 가 갖는다.

### 책임

| 모듈 | 맡는 것 |
| --- | --- |
| 대시보드 plugin | `PUT /api/config` 의 `ensure_image_service`. 이미지 서비스 값과 「이미 있으면 덮어쓰지 않는다」 를 갖는다 |
| `backend` 의 `hermes` | `image_gen` 이 든 도구 저장에 그 칸을 넣어 보낸다 |
| `backend` 의 `agent` | 기다리는 목록(`agent.pending_default_toolsets`)의 쓰기, 요청 밖에서 다시 켜기, 관리자 알림, 사람의 저장 뒤 정리 |
| `backend` 의 `people` | 새 기본 비서를 만들 때 기다리는 목록에 적고, 기본 도구를 켠 뒤 한 번 켜 본다 |
| `backend` 의 `orchestration` | 도구 판정에서 사람이 없는 실행의 `image_generate` 를 막는다 |
| `web` | 알림 종류 하나를 더 안다. 화면은 바꾸지 않는다 |

### 실행 공간 준비 가운데 Control Plane 이 하는 범위

스펙 DECISION31 이 먼저 확인하라고 한 것이다.

| 단계 | 누가 하나 |
| --- | --- |
| 주인의 첨부 디렉터리 만들기 | Control Plane. 도구 저장 전에 만든다 |
| 사용자 작업 디렉터리 만들기, profile 의 `terminal:` 을 실행 공간 설정으로 쓰기 | 대시보드 plugin. 도구 저장 때 한다 |
| profile 을 실행 공간 정책에 등록하기 | 운영. 정책은 대시보드 프로세스의 환경 값이라 Control Plane 이 바꾸지 못한다 |

Control Plane 이 스스로 하는 일은 앞의 둘을 일으키는 도구 저장을 되풀이하는 것이다. 마지막 단계가 남았으면 plugin 이 409 `sandbox_unavailable` 로 답하고, 그것을 관리자 알림으로 남긴다.

### 이미지 서비스 넣기

`PUT /api/config` 본문에 `ensure_image_service`(참 거짓)를 더한다. 계약은 [대시보드 plugin README](../../../hermes/plugins/dashboard-profile-api/README.md) 가 갖는다.

- 참이면 `platform_toolsets.api_server` 에 `image_gen` 이 있어야 한다. 없으면 400 이다
- 저장된 설정의 `image_gen.provider` 가 비었을 때만 서비스를 넣는다. `image_gen` 의 다른 키와 이미 있는 서비스는 그대로 둔다(스펙 DECISION30)
- plugin 은 검사한 설정을 Hermes 처리기보다 먼저 쓰고 처리기가 실패하면 되돌린다. 그래서 서비스만 들어가거나 도구만 켜진 상태가 남지 않는다
- 실행 공간 판정이 먼저다. 정책에 없는 profile 은 409 이고 서비스도 쓰지 않는다
- Control Plane 은 `image_gen` 이 든 모든 도구 저장에 이 칸을 넣어 보낸다. 사람의 저장, 기본 도구 적용, 기다리는 도구 켜기가 같은 길이다. 이 칸을 모르는 옛 plugin 은 본문을 400 으로 거절하므로 plugin 을 먼저 배포한다(rule-66)

### 기다리는 목록

`agent.pending_default_toolsets` 는 Control Plane 이 스스로 켜려고 기다리는 기본 도구 이름을 쉼표로 이은 글이다. 지금은 `image_gen` 하나만 들어간다(`AgentToolPolicy.retriedDefaults`).
엔티티 저장은 이 칸을 쓰지 않고 전용 쿼리만 쓴다. 먼저 읽어 둔 에이전트를 나중에 저장해도 비운 값이 되살아나지 않는다(rule-68).

| 때 | 하는 일 |
| --- | --- |
| PR2 배포의 마이그레이션 | 지우지 않은 기본 비서(주인의 기본 profile 을 가리키는 `PRIVATE` 에이전트)에 `image_gen` 을 넣는다. 한 번만 돈다 |
| 첫 로그인 | 새 기본 비서를 저장하는 트랜잭션에서 기본 도구 가운데 `image_gen` 을 넣는다. 커밋 뒤 기본 도구를 켜고 곧바로 한 번 켜 본다 |
| 5분마다(`assistant.agents.pending-toolsets-cron`) | 목록이 있는 에이전트를 하나씩, 행을 잠근 트랜잭션에서 켜 본다 |
| 사람의 도구 저장이 끝났다 | 저장 뒤 켜진 이름과, 저장 전에 켜져 있던 이름을 목록에서 뺀다. 꺼진 채 그대로인 이름은 남긴다 |

켜 보는 순서는 `AgentDefaultToolsets.enablePending` 이 갖는다.

| 상황 | 결과 |
| --- | --- |
| 그룹에 공개했거나 주인이 없다 | 켜지 않고 목록을 비운다(스펙 DECISION21) |
| 비밀값을 요청하는 올린 스킬이 있다 | 켜지 않고 기다린다. 관리자에게 알린다 |
| 실행 공간 정책에 없다(409 `sandbox_unavailable`) | 다른 도구도 쓰지 않고 기다린다. 관리자에게 알린다 |
| 켰다 | 지금 켜진 도구에 더해 `require_sandbox` 로 쓰고, 다시 읽어 켜졌는지 본 뒤 목록을 비우고 알림을 읽음으로 바꾼다. 이미 켜져 있던 도구도 한 번은 다시 쓴다. 도구만 켜져 있고 이미지 서비스 설정이 없는 profile 에 설정이 들어간다 |
| Hermes 가 답하지 않거나 설정 쓰기를 거절한다 | 기다린다. 실패가 세 번 이어지면 관리자에게 알린다. 이어진 횟수는 메모리에만 둔다 |

**관리자가 끈 비서는 다시 켜지 않는다(스펙 DECISION23).** 목록에 이름을 넣는 길은 마이그레이션 한 번과 첫 로그인뿐이다. 켜진 뒤나 관리자가 끈 뒤에는 목록이 비어 있어 배포와 재시작, 주기 작업이 그 비서를 보지 않는다.

### 관리자 알림

종류는 `DEFAULT_TOOLS_PENDING` 이고 받는 사람은 주인 그룹의 차단되지 않은 관리자다. 본문에 사용자 표시 이름과 켜지 못한 도구, 까닭이 있다. 누르면 갈 곳은 없다.
같은 에이전트의 같은 까닭은 한 번만 알린다. 에이전트 번호와 까닭으로 만든 원인 식별자가 이미 있는지로 본다. 까닭이 바뀌면 다시 알리고, 오래된 알림 정리가 줄을 지운 뒤에도 기다리고 있으면 다시 알린다.
켜지거나 더 기다리지 않게 되면 그 에이전트의 알림을 읽음으로 바꾼다.

### 사람이 없는 실행

`EventToolGuard` 가 도구 판정 요청의 도구 이름을 받는다. 실행을 찾은 호출(등록된 자식의 origin 실행, 루트 session 에서 도는 실행)에서 도구가 `image_generate` 이고 그 실행의 트리 루트가 사람이 없는 실행이면 막는다.
트리 루트의 판정은 기존 `SessionOwnerResolver.unattendedTree` 다(스펙 DECISION26). 예약 작업 turn, 먼저 살펴보기 turn(단추로 연 것 포함), 그 결과를 전하는 turn, 그 실행들이 맡긴 하위 실행이 모두 여기 든다. 같은 대화라도 사람이 보낸 메시지의 turn 은 루트가 달라 통과한다.
막힌 호출에 모델이 받는 글은 그림을 만들 수 없다는 것과 결과에 그렇게 적으라는 것이다. 사건 살펴보기 트리는 지금처럼 그 앞의 판정이 먼저 막는다.
실행을 찾지 못한 호출은 Control Plane 이 연 실행이 아니라서 이 판정으로 막지 않는다.

### 실패와 일관성

| 무엇 | 어떻게 되나 |
| --- | --- |
| 옛 plugin 에 새 Control Plane 이 `image_gen` 저장을 보낸다 | 400 으로 거절돼 도구가 켜지지 않는다. 사람의 저장은 오류를 보이고, 기다리는 비서는 다음 차례에 다시 본다 |
| 새 plugin 에 옛 Control Plane 이 저장을 보낸다 | 칸이 없어 지금처럼 toolset 만 쓴다 |
| 주기 작업과 사람의 도구 저장이 겹친다 | 둘 다 에이전트 행을 잠근다. 주기 작업은 잠금을 기다리고, 사람의 저장은 잠겨 있으면 `AGENT_BUSY` 다 |
| 켠 직후 서버가 죽어 목록을 비우지 못했다 | 다음 차례가 같은 목록을 한 번 더 쓰고 비운다 |
| 주기 작업이 행을 잠근 사이 그 비서의 사진이 있는 turn 이 시작된다 | 그 turn 의 원본 조회 도구 확인은 잠금을 기다리지 않아 실패할 수 있다. 겹치는 시간은 5분마다 Hermes 호출 두세 번이고 기다리는 비서에서만 생긴다. 다시 보내면 된다 |
| 켜고 목록을 비우는 사이 관리자가 끈다 | 행 잠금이 순서를 정한다. 주기 작업이 먼저면 관리자의 저장이 뒤에 끄고 목록은 이미 비어 있다 |
| 기다리는 에이전트를 지웠다 | 주기 작업이 지운 에이전트를 보지 않는다 |

## PR3: 지금 대화의 사진과 그림을 바탕으로 고치기

근거와 기각한 대안은 [ADR-20261120 / image-source](../../adr/ADR-20261120-image-source.md) 가 갖는다.

### Hermes 가 받는 것

Hermes v0.23.2 의 `image_generate` 는 바탕 한 장 `image_url` 과 참고 목록 `reference_image_urls` 를 받는다. 이미지 서비스(openai-codex)는 둘을 이은 앞의 16장까지 `images/edits` 로 보내고 넘는 것은 말없이 버린다.
값은 주소, data URL, 파일 경로 가운데 하나다. 실행 공간이 있는 profile 은 파일 경로를 그 profile 의 media cache(Hermes 홈의 `cache/` 아래) 안에서만 호스트에서 읽고, 나머지는 실행 공간 안에서 읽는다.
**실행 공간에는 그 사용자의 모든 대화의 사진이 붙어 있다**([ADR-091](../../adr/ADR-091-사진-첨부는-사용자별로-저장하고-실행-공간에는-그-사용자만-붙인다.md)). 경로를 그대로 두면 다른 대화의 사진을 쓸 수 있어 REQ16 을 지키지 못한다.

### 책임

| 모듈 | 맡는 것 |
| --- | --- |
| `hermes/plugins/fos-ctx` | `image_generate` 의 두 인자를 읽어 가리킴만 받고, 하나씩 Control Plane 에서 받아 media cache 에 쓰고 인자를 바꾼다. 결과 hook 에서 그 사본을 지운다. 누구의 어느 대화인지는 정하지 않는다 |
| `backend` 의 `mcp` | 서명한 호출 맥락에서 origin 실행의 사용자와 대화를 정하고 가리킴을 푼다 |
| `backend` 의 `chat.attachment` | 요청자가 그 대화에 보낸 사진의 원본을 읽는다. 원본 조회 도구와 같은 판정이다 |
| `backend` 의 `chat.artifact` | 그 대화의 그림 행을 제목으로 찾아 파일을 읽는다. 실행 입력에 고치기 규칙과 그림 제목 목록을 넣는다 |

화면은 바꾸지 않는다. 고친 그림은 새 그림이라 PR1 의 길로 답에 보인다.

### 가리킴

| 값 | 뜻 |
| --- | --- |
| `attachment:<첨부 번호>` | 그 대화의 사진. 첨부 번호는 실행 입력의 사진 참조 목록이 주는 `attachment_id` 다(`attachment_inspect` 와 같은 번호) |
| `generated:<제목>` | 그 대화에서 만든 그림. 제목은 결과물 파일 이름에서 확장자를 뗀 것이다 |

두 인자의 값이 모두 가리킴이어야 한다. 경로, 주소, data URL 이 하나라도 있으면 막는다. 합쳐 16장을 넘으면 막고 줄여 달라고 알린다(스펙 DECISION25). 같은 가리킴이 두 번 오면 한 번만 받는다.
16 은 Hermes 의 openai-codex plugin 의 `_MAX_REFERENCE_IMAGES` 다. 이 저장소가 정한 값이 아니라 계약 검사가 Hermes 를 올릴 때 다시 본다.

### fos-ctx 의 hook

| 때 | 하는 일 |
| --- | --- |
| `pre_tool_call`, 도구가 `image_generate` 이고 두 인자 가운데 하나라도 있다 | 도구 판정을 통과한 뒤 제목 줄 떼기와 함께 돈다. 가리킴을 차례로 받는다. 모두 받으면 `{"action": "modify", "args": {"image_url", "reference_image_urls", "prompt"?}}` 로 사본 경로를 넣는다. 하나라도 받지 못하면 받은 사본을 지우고 막는다 |
| `transform_tool_result`, 도구가 `image_generate` | 그 호출의 사본을 지운 뒤 PR1 의 올리기를 한다 |

- 사본은 Hermes 홈의 `cache/fos-image-sources/` 에 무작위 이름으로 쓴다. 실행 공간이 있는 profile 이 호스트에서 읽을 수 있는 자리다. 이미지 서비스가 바이트를 data URL 로 바꾸므로 Hermes 기록과 실행 사건에는 경로만 남는다
- 사본마다 `(session_id, tool_call_id)` 를 기억해 결과 hook 이 지운다. 결과 hook 이 돌지 못한 사본은 다음 쓰기 때 1시간이 지난 것부터 지운다
- 받기의 제한 시간은 한 호출의 모든 가리킴을 합쳐 15초다. Hermes 의 callback 제한(기본 30초) 안에서 도구 판정과 함께 끝내야 한다
- `tool_call_id` 나 session 이 없는 호출(코드 실행 안의 호출)은 가리킴을 받지 못해 막는다

막을 때 모델이 받는 글은 아래 뜻이다. 가리킴마다 까닭을 하나씩 적는다.

| 까닭 | 글 |
| --- | --- |
| 가리킴이 아니다 | 바탕과 참고 사진에는 `attachment:<첨부 번호>` 나 `generated:<제목>` 만 쓴다 |
| 16장을 넘는다 | 한 번에 쓸 수 있는 사진은 16장까지다. 사용자에게 줄여 달라고 한다 |
| `IMAGE_SOURCE_UNKNOWN` | 이 대화에 보낸 사진이나 이 대화에서 만든 그림이 아니다 |
| `IMAGE_SOURCE_GONE` | 지웠거나 보관 기간이 지나 쓸 수 없다 |
| 그 밖의 답, 제한 시간 | 사진을 받지 못했다. 잠시 뒤 다시 부탁하라고 한다 |

모든 막는 글은 「그림을 만들지 않았다. 사용자에게 어느 사진을 왜 쓸 수 없는지 알린다」 로 끝난다(스펙 DECISION32).
404 는 이 경로가 없는 옛 Control Plane 이다. 고치기를 아직 쓸 수 없다고 막는다. 가리킴 없이 경로를 받던 동작으로 돌아가지 않는다. 그 동작이 다른 대화의 사진을 읽을 수 있기 때문이다.

### 받기 경로

`POST /internal/hermes/image-source`. 인증은 `/mcp` 와 같은 profile 토큰이다.
본문은 JSON `{"_fos_ctx": <도구 이름 image_generate 로 서명한 호출 맥락>, "source": "<가리킴>"}` 다. 맥락의 모양과 서명은 `/mcp` 와 같다.

| 판정 | 응답 |
| --- | --- |
| 맥락이 맞지 않거나 origin 실행을 찾지 못하거나, origin 실행이나 그 트리의 루트가 중지됐거나, 대화 없는 실행이다 | 403 `MCP_CALL_CONTEXT_INVALID` |
| 가리킴의 모양이 틀렸다. 그 대화에 없는 첨부 번호, 요청자가 보내지 않은 사진, 그 대화에 없는 제목이다 | 422 `IMAGE_SOURCE_UNKNOWN` |
| 사진을 지웠거나 보관 기간이 지났다. 그림 파일이 보관 기간 정리로 지워졌다 | 410 `IMAGE_SOURCE_GONE` |
| 받았다 | 200. 본문은 원본 바이트 그대로, `Content-Type` 은 그 형식, `Cache-Control: no-store` |

- origin 판정은 PR1 의 올리기와 같다(`SessionOwnerResolver`). 맡긴 실행이 부모 turn 뒤에 돌기 때문에 끝난 origin 도 받는다
- 사진은 `AttachmentService` 의 원본 조회 판정(그 대화, 요청자가 올리고 보낸 것, 지우지 않았고 보관 기간 안)을 그대로 쓰고 같은 20MiB 상한과 머리 바이트 검사를 한다
- 그림은 그 대화의 그림 행 가운데 보관 사본이 아닌 행을 제목으로 찾는다. 대화 주인이 요청자가 아니면 `IMAGE_SOURCE_UNKNOWN` 이다. 상한은 PR1 의 올리기 상한이다
- **이 경로는 404 를 내지 않는다.** 저장 중의 `*_NOT_FOUND` 는 `IMAGE_SOURCE_UNKNOWN` 으로 바꾼다. 남의 대화의 번호와 없는 번호가 같은 응답이라 번호를 훑어 남의 것을 알아내지 못한다

### 실행 입력의 그림 규칙

`ArtifactService.IMAGE_RULES` 에 아래 뜻의 줄을 더한다. 조건 줄(도구가 있고 사용자가 그림을 부탁했을 때만)은 그대로다.

- 지금 대화의 사진이나 그림을 바탕으로 고칠 때는 바탕을 `image_url` 에, 참고를 `reference_image_urls` 에 가리킴으로 넣는다. 경로나 주소를 쓰지 않는다
- 고치는 지시문에는 바꿀 곳, 그대로 지킬 곳, 참고 사진의 어느 부분을 쓰는지를 적는다. 올린 사진은 대화에서 몇 번째 사진인지로, 만든 그림은 제목으로 가리키고 입력 순서(바탕이 첫째)를 함께 적는다
- 쓸 수 없다는 결과를 받으면 그림 없이 어느 사진을 왜 쓸 수 없는지 답한다

그 대화에 파일이 남은 그림이 있으면 단락 끝에 「이 대화에서 만든 그림: 「제목」, …」 줄을 둔다. 최근 20장까지다. 모델이 Hermes 기록을 압축한 뒤에도 제목을 안다.

### 실패와 일관성(PR3)

| 무엇 | 어떻게 되나 |
| --- | --- |
| 새 fos-ctx 에 옛 Control Plane | 가리킴이 든 호출은 404 를 받아 막힌다. 가리킴이 없는 그림 만들기는 그대로다 |
| 옛 fos-ctx 에 새 Control Plane | 모델이 가리킴을 적으면 Hermes 가 파일 경로로 읽다 실패하고 모델이 실패를 알린다. 플러그인을 먼저 배포한다 |
| 받은 뒤 이미지 도구가 돌기 전에 사진이 지워진다 | 이미 받은 사본으로 만든다. 사본은 결과 hook 이 지운다 |
| 결과 hook 이 돌지 못했다 | 사본이 media cache 에 남는다. 다음 받기가 1시간이 지난 사본을 지운다 |
| 이미지 서비스가 입력 그림을 거절한다 | Hermes 의 오류 결과가 모델에게 가고 PR1 처럼 모델이 까닭을 답한다 |

## PR4: 만든 그림을 블로그 원고에 넣기

근거와 기각한 대안은 [ADR-20261120 / generated-image-draft](../../adr/ADR-20261120-generated-image-draft.md) 가 갖는다.

### 원고가 사진을 쓰는 길

원고의 사진 자리는 사진 첨부(`chat_attachment`) 번호만 가리킨다. 그 번호로 아래가 모두 돈다([`content-draft.md`](../content-draft.md)).

| 단계 | 사진을 쓰는 방법 |
| --- | --- |
| 초안 미리보기(`render_draft`) | 대화의 사진 폴더(`photo_dir`)에서 `<첨부 번호>.<확장자>` 파일을 확인하고, HTML 이 대화의 첨부 주소로 그 사진을 부른다 |
| 원고 저장(`content_draft_write`) | 그 대화에 요청자가 올린 첨부인지, 지우지 않았고 보관 기간 안인지 보고 원본 지문을 고정한다. 원고의 기한은 첨부 기한 가운데 가장 이른 것이다 |
| 승인 카드 | 첨부 주소로 사진을 그린다 |
| 네이버 임시저장 worker | 사진 폴더 하나에서 첨부 파일을 읽고 지문을 맞춘 뒤 올린다. 모든 사진이 같은 폴더여야 한다 |

만든 그림은 대화의 결과물 폴더에 있고 `chat_artifact` 행이다. 이 길에 그림을 따로 넣으면 원고 스키마와 두 지문, Java 재계산, worker 의 사진 폴더 규칙을 함께 바꿔야 한다.

### 사진 첨부 사본

**그림을 원고에 넣을 때 Control Plane 이 그 그림의 사진 첨부 사본을 같은 대화에 만든다.** 사본은 보통 사진 첨부와 같은 폴더와 같은 파일 이름 규칙을 쓰므로 위 네 단계가 고칠 것 없이 쓴다.

- 사본 행은 `chat_attachment.source_artifact_id` 에 그 그림 행 번호를 적는다. 이 칸이 있으면 원고용 사본이다
- 올린 사람은 대화 주인이고 `message_id` 는 비어 있다. 보관 기간은 사진을 올릴 때와 같이 만든 때부터 센다(`assistant.attachment.retention-days`)
- 사본은 메시지에 묶이지 않으므로 대화 화면, 사진 순번, 실행 입력의 사진 참조 목록, 원고 미리보기의 「빠진 사진」 에 나오지 않는다. 사용자가 「3번 사진」 이라고 부르는 번호가 바뀌지 않는다
- 보내지 않은 사진의 장수 상한과 메시지에 묶기에서 사본을 뺀다. 사용자가 사진을 올리는 데 영향이 없고 사본이 사용자 메시지에 붙지 않는다
- 원고의 사진 판정(`ContentDraftReferences`)은 「메시지에 묶였거나 원고용 사본이다」 로 넓힌다. 나머지 판정(대화, 올린 사람, 삭제, 보관 기간)은 같다
- 원고 snapshot 의 `ordinal` 은 보낸 사진 뒤에 첨부 번호 순서로 이어 붙인다. compiler 의 스키마가 요구하는 값이고 지문과 화면에 쓰지 않는다
- 원본 조회 도구(`attachment_inspect`)는 사본을 받지 않는다. 모델은 그림을 이미 안다

사본은 원고용으로만 만든다. 그림 행을 처음부터 사진 첨부로 두면 대화 화면의 사진 순번과 사진 참조 목록에 섞인다.

### 도구

`content_draft_image` 를 Control Plane MCP 에 더한다. 원고 도구와 같은 사전·사후 검사(`McpContentDraftTools.guarded`)와 오류 변환을 쓴다. 살펴보기 대화와 메시지 없는 자동 작업 대화에는 원고 도구처럼 쓸 수 없다.

| 인자 | 뜻 |
| --- | --- |
| `title` | 그 대화에서 만든 그림의 제목. 1~200자. 실행 입력의 「이 대화에서 만든 그림」 줄과 같은 글자다 |

| 결과 | 응답 |
| --- | --- |
| 넣을 수 있다 | `{"assetId": "<첨부 번호>", "fileName": "<첨부 번호>.<확장자>", "photoDir": "<사진 폴더>", "title": "<제목>"}`. 원고의 `assetId` 와 초안의 `[사진 N: <fileName>]`, `photo_dir` 에 쓴다. 사진 폴더는 사진 참조 목록 위에 적는 폴더와 같다. 사진을 올리지 않은 대화에는 그 목록이 없어 여기서 준다 |
| 그 제목의 그림이 없다 | `IMAGE_SOURCE_UNKNOWN` 과 고정 안내 |
| 그림 파일이 보관 기간 정리로 지워졌다 | `IMAGE_SOURCE_GONE` 과 고정 안내. 사본이 있어도 돌려주지 않는다(REQ19) |

- 그 대화의 같은 그림으로 만든 사본이 아직 쓸 수 있으면(지우지 않았고 보관 기간 안이고 파일이 있다) 새로 만들지 않고 그 사본을 돌려준다. 원고를 고쳐 다시 넣어도 사본이 늘지 않는다. 그림 파일이 지워졌으면 사본이 있어도 거절한다(REQ19). 이미 넣은 원고는 사본 번호를 그대로 쓰므로 고쳐 저장할 수 있다
- 찾기부터 사본 행과 파일 쓰기까지 `ChatContentMutationCoordinator` 의 사용자와 대화 잠금 안에서 한다. 같은 그림을 동시에 넣어도 사본이 하나다
- 파일은 행 번호를 받은 뒤 쓰고, 트랜잭션이 되돌려지면 쓴 파일을 지운다
- 그림 행은 보관 사본이 아닌 행을 제목으로 찾는다(PR3 의 `GeneratedImageService.readByTitle` 과 같은 판정). 크기 상한도 같다. 그림 바이트는 사본을 새로 만들 때만 읽는다
- 사람이 없는 실행에서 막지 않는다. 이 도구는 이미 만든 그림을 복사할 뿐 구독 한도를 쓰지 않는다

### 실행 입력과 스킬

- 네이버 블로그 스킬의 「초안」 과 「새 글 저장」 에 만든 그림을 넣는 법을 더한다. 사용자가 그림을 글에 넣어 달라고 하면 `content_draft_image` 로 받은 `fileName` 과 `photoDir` 을 초안에, `assetId` 를 원고의 사진 자리에 쓴다
- 실행 입력의 그림 규칙은 바꾸지 않는다. 그림 제목 목록(PR3)이 이미 매 turn 들어간다

### 실패와 일관성(PR4)

| 무엇 | 어떻게 되나 |
| --- | --- |
| 사본을 만든 뒤 원고를 저장하지 않았다 | 사본은 화면에 보이지 않은 채 보관 기간이 지나면 사진 정리가 지운다 |
| 사본을 넣은 원고의 그림 파일이 보관 기간 정리로 지워졌다 | 원고는 사본만 보므로 그대로다. 사본의 보관 기간이 끝나면 사진과 같이 원고가 STALE 이 된다(REQ19) |
| 사용자가 대화를 지웠다 | 기존 대화 정리가 사본도 함께 지운다 |
| 새 Control Plane 에 옛 fos-ctx | fos-ctx 는 Control Plane 의 모든 MCP 도구에 맥락을 서명하므로 도구가 돈다. 서명하지 못했을 때 막는 목록에만 빠져 있다 |
| 옛 Control Plane 에 새 스킬 | 도구가 없어 모델이 그림을 넣지 못한다고 답한다. Control Plane 을 먼저 배포한다 |

마이그레이션은 칸 하나를 더하기만 한다. 옛 행은 모두 NULL 이라 지금과 같다.

## 실패와 일관성

| 무엇 | 어떻게 되나 |
| --- | --- |
| 올리기 hook 이 예외를 내거나 제한 시간을 넘긴다 | hook 안에서 모든 예외를 잡아 경로 없는 오류 결과로 바꾼다. Hermes 가 hook 자체를 건너뛴 때만 원래 결과(경로)가 모델에게 간다. 그때 모델이 답에 적은 경로는 아래 「답 글의 경로 가리기」 가 뺀다 |
| hook 이 제한 시간 뒤에 올리기를 끝낸다 | 그림은 저장되고 답에 묶인다. 모델은 원래 결과를 받았으므로 답 글과 그림이 함께 보인다 |
| 같은 올리기가 두 번 온다 | `generation_call_key` 가 같은 행을 찾아 처음 결과를 돌려준다. 파일을 다시 쓰지 않는다. 같은 호출 번호라도 그림이 다르면 다른 그림으로 저장한다 |
| 파일은 썼는데 행을 만들지 못한다 | 올리기는 실패로 답하고 쓴 파일을 지운다. 지우지 못한 파일은 행이 없어 보이지 않고 보관 기간 정리가 지운다 |
| turn 이 답 없이 끝난다 | 행은 `message_id` 가 빈 채 남고 그 대화의 다음 답에 묶인다. 다음 답이 없으면 보이지 않은 채 보관 기간 정리를 따른다 |
| 그림을 만드는 사이 실행이 중지됐다 | origin 실행이나 그 트리의 루트가 중지됐으면 올리기를 거절한다. 기존 origin 판정(`SessionOwnerResolver`)을 그대로 따른다. 끝난 origin 은 받는다. 맡긴 실행이 부모 turn 뒤에 돌기 때문이다 |
| 보관 기간 정리 | 그림 행도 기존 규칙을 따른다. 파일을 지우면 `deleted_at` 을 적고 지시문 칸은 남긴다(REQ9) |
| 대화 정리 | 기존 `deleteAllOf` 가 그림 행과 지시문을 함께 지운다. 보관본은 남는다 |

PR1 의 배포 순서는 자유다. Control Plane 을 먼저 올리면 새 경로를 아무도 부르지 않고, fos-ctx 를 먼저 올리면 404 를 받아 결과를 바꾸지 않는다. 마이그레이션은 칸을 더하기만 한다. PR3 은 fos-ctx(대시보드 plugin 이 profile 에 설치한다)를 먼저 배포한다. 「실패와 일관성(PR3)」 을 본다. PR4 는 Control Plane 을 먼저 배포한다. 「실패와 일관성(PR4)」 을 본다.

## 검증 전략

| 요구사항 | 어디서 확인하나 |
| --- | --- |
| REQ1, REQ2, REQ6, REQ10 의 저장과 권한 | Control Plane 통합 테스트. 서명한 올리기 요청으로 파일과 행, 이름 번호, 되풀이 요청, 다른 사용자의 접근 거절, 끝난 실행의 거절을 본다 |
| REQ1 의 답에 묶기, REQ7 의 자동 보관 | 기존 `ArtifactService` 와 자동 보관 테스트에 그림 행 사례를 더한다 |
| Hermes 에 기대는 동작 | `hermes/tests` 의 계약 검사. 지정한 Hermes 소스에서 `image_generate` 결과의 모양과 캐시 폴더, 인자 병합이 그대로인지 본다 |
| REQ1, REQ4, REQ6 의 hook | fos-ctx 의 Python 단위 테스트. 제목 줄 떼기, 올리기 본문과 서명, 결과 바꾸기, 실패와 404 를 본다. 서명 확인 값은 Control Plane 테스트와 같은 값을 쓴다 |
| REQ4 의 단락 | 실행 입력을 만드는 기존 테스트에서 단락의 규칙 문장을 본다 |
| REQ2, REQ3, REQ5, REQ7, REQ9 의 화면 | 화면 단위 테스트와 브라우저 테스트. 넓은 화면과 휴대폰 폭에서 그림, 크게 보기, 지시문 펼치기, 지워진 그림의 링크를 본다 |
| REQ8 | 보관함 목록의 화면 테스트 |
| REQ11 | `FirstAgentCreatorTest` 가 새 기본 비서의 도구 목록과 기다리는 목록을, `AgentPendingToolsetsTest` 가 마이그레이션이 기본 비서만 고르는지를 본다. `MEMBER` 역할의 거절은 기존 `AgentToolPolicyTest` 가 본다 |
| REQ12 | `hermes/tests` 의 `test_dashboard_profile_api_image_service.py` 가 서비스 넣기, 덮어쓰지 않기, 실패 때 되돌리기를 본다. `HermesToolsetRequestTest` 가 Control Plane 이 보내는 본문을 본다 |
| REQ13 | `AgentPendingToolsetsTest`. 실행 공간이 없을 때의 관리자 알림 한 건, 준비된 뒤 스스로 켜기, 관리자가 끈 뒤 다시 켜지 않기를 본다 |
| REQ14 | `ToolGuardEndpointTest`. 사람이 없는 실행과 그 자식의 `image_generate` 가 막히고 사람이 보낸 turn 은 통과하는지 본다 |
| REQ15, REQ16 의 받기 | `ImageSourceEndpointTest`. 사진 세 장과 만든 그림을 원본 바이트 그대로 받고 받은 뒤에도 원본이 같은지, 다른 대화와 다른 사용자의 사진, 지운 사진, 없는 제목, 지워진 그림이 거절되고 어느 것도 404 가 아닌지 본다 |
| REQ15, REQ16 의 hook | `hermes/tests/test_image_source.py`. 가짜 Control Plane 으로 세 장을 받아 인자를 사본 경로로 바꾸는지, 하나라도 못 받으면 막고 사본을 지우는지, 가리킴이 아닌 값과 16장 초과, 옛 Control Plane 을 막는지, 결과 hook 이 사본을 지우는지 본다 |
| REQ15 의 Hermes 쪽 | `hermes/tests/test_generated_image_contract.py`. 두 인자의 이름과 media cache 의 호스트 읽기, 16장 상한을 Hermes 소스에서 본다 |
| REQ17 | 실행 입력을 만드는 기존 테스트에서 고치기 규칙과 그림 제목 줄을 본다 |
| REQ18 | `ContentDraftImagesTest` 가 사본이 그림과 바이트가 같고 그 번호로 원고를 저장하는지, 같은 그림을 두 번 넣어도 사본이 하나인지, 사본이 사진 순번과 사진 참조 목록, 보내지 않은 사진 수, 메시지 묶기에 나오지 않는지 본다. `ContentDraftSnapshotsTest` 가 snapshot 입력에서 사본이 사진과 같은 폴더의 파일이고 그 파일이 그림과 같은지, `ContentDraftImageToolTest` 가 MCP 결과를 본다. `ContentDraftJobLoopbackTest` 가 사본을 넣은 원고를 승인해 합성 worker 가 가짜 네이버에 올린 바이트의 지문이 그림과 같은지 본다 |
| REQ19 | `ContentDraftImagesTest` 가 보관 기간 정리로 파일을 지운 그림은 사본이 있어도 `IMAGE_SOURCE_GONE` 이고 사본을 만들지 않는지, 이미 넣은 원고는 그림 파일을 지운 뒤에도 남아 사본 번호로 고쳐 저장되고 사진의 보관 기간이 지나면 쓸 수 없는지 본다. `ContentDraftImageToolTest` 가 모델이 받는 고정 안내를 본다 |

실제 이미지 서비스로 한 장을 만드는 왕복은 배포 뒤 운영에서 확인한다(AGENTS.md 「Hermes 연동을 바꿨으면 배포 뒤 왕복시켜 본다」).

운영에서 gpt-image-2 로 만든 그림 한 장은 2,517,146바이트의 PNG 이고 1254x1254 픽셀이었다(2026-11-19, 정사각형 요청, medium 품질, 표본 1장).
이 설계를 쓸 때의 보관함 상한 5MiB 안이다(지금은 25MiB 다). 가로나 세로가 긴 그림과 high 품질은 더 클 수 있어, 배포 뒤 그런 그림에서 「보관 실패」 가 나오는지 본다. 자주 나오면 상한을 사용자에게 다시 묻는다(스펙 DECISION20).

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 그림을 Control Plane 으로 옮기는 길 | fos-ctx 의 결과 hook 이 내부 경로로 올린다 | 작성자 | [ADR-20261119 / generated-image](../../adr/ADR-20261119-generated-image.md) |
| DECISION2 | 제목을 받는 방법 | 지시문 첫 줄의 `제목:` 줄을 fos-ctx 가 뗀다 | 작성자 | `image_generate` 의 인자는 Hermes 가 정해 제목 칸을 더할 수 없다. 떼지 않으면 이미지 서비스가 그 글자를 그림에 그릴 수 있다 |
| DECISION3 | 제목과 지시문을 두는 곳 | 제목은 파일 이름, 지시문은 `chat_artifact` 의 암호화한 새 칸 | 작성자 | 새 표를 만들지 않는다(사용자 결정 2026-11-01). 파일 이름이 제목이면 보관함과 내려받기가 고칠 것 없이 제목을 쓴다 |
| DECISION4 | 그림 지침을 주는 자리 | 매 turn 붙이는 결과물 폴더 단락 | 작성자 | 그 단락이 이미 「이미지는 `source_url`」 을 안내해 함께 고쳐야 한다. Control Plane 은 그 에이전트에 `image_gen` 이 켜졌는지 저장하지 않아 켜진 에이전트에만 붙일 수 없다 |
| DECISION5 | 올리기 상한 | 20MiB, 설정 값 | 작성자 | 보관함 상한(당시 5MiB)보다 큰 원본도 대화에는 보여야 한다. 측정한 한 장은 약 2.4MiB 였다(「검증 전략」) |
| DECISION6 | 답 글에 남은 Hermes 쪽 경로 | 저장된 답은 고치지 않고, 내보낼 때 `MEDIA:` 태그와 Hermes 이미지 캐시 경로를 뺀다. 메시지 응답과 화면의 흐르는 답 모두에 적용한다. 이 PR 배포 전에 저장된 옛 답에도 같다 | 사용자 2026-11-19 | 운영에서 답에 홈서버의 내부 경로가 보였고 가려 달라는 요청이 있었다. 경로는 먼저 모델에게 가는 결과에서 빼고, 이 가리기는 그 뒤에 남는 경우를 막는다 |
| DECISION7 | 실행 공간 준비 가운데 Control Plane 이 하는 범위 | 도구 저장을 되풀이해 디렉터리와 `terminal:` 쓰기를 일으키는 데까지다. 정책 등록은 운영에 남기고 관리자 알림으로 알린다 | 작성자 | [ADR-20261119 / image-gen-defaults](../../adr/ADR-20261119-image-gen-defaults.md). 정책은 대시보드 프로세스의 환경 값이다 |
| DECISION8 | 기다리는 상태를 두는 곳 | `agent` 의 새 칸 하나 | 작성자 | 새 표를 만들지 않는다(사용자 결정 2026-11-01). 재시작 뒤에도 남아야 한다 |
| DECISION9 | 이미지 서비스 값을 갖는 곳 | 대시보드 plugin. Control Plane 은 넣으라는 표시만 보낸다 | 작성자 | 서비스가 하나다(스펙 DECISION5). 값이 두 곳에 생기지 않는다 |
| DECISION10 | 사람이 없는 실행을 막는 자리 | 이미 있는 도구 판정 경로 | 작성자 | fos-ctx 가 `image_generate` 를 이미 묻고 요청에 도구 이름이 있다. plugin 을 바꾸지 않는다 |
| DECISION11 | 바탕 사진을 이미지 도구에 넘기는 길 | 모델은 가리킴만 적고 fos-ctx 가 Control Plane 에서 받아 media cache 사본 경로로 바꾼다 | 작성자 | [ADR-20261120 / image-source](../../adr/ADR-20261120-image-source.md). 실행 공간의 경로를 그대로 두면 다른 대화의 사진을 쓸 수 있다 |
| DECISION12 | 사본을 넘기는 모양 | data URL 이 아니라 파일 경로 | 작성자 | 고친 인자는 Hermes 의 도구 사건과 결과 hook 에 그대로 간다. 수 MB 의 base64 가 실행 사건으로 Control Plane 까지 흐르지 않게 한다 |
| DECISION13 | 사진을 가리키는 번호 | 도구 인자는 첨부 번호(`attachment_id`), 지시문과 답은 대화 안의 순번 | 작성자 | 모델은 매 turn 사진 참조 목록에서 둘을 함께 받는다. 첨부 번호는 원본 조회 도구가 이미 쓰는 값이고 사람이 보는 글에는 순번을 쓴다(스펙 DECISION28) |
| DECISION14 | 옛 Control Plane(404)에서 | 가리킴이 든 호출을 막는다 | 작성자 | 경로를 받던 동작으로 돌아가면 다른 대화의 사진을 읽을 수 있다. 막아도 가리킴 없는 그림 만들기는 그대로다 |
| DECISION15 | 그림을 원고에 넣는 길 | 원고에 넣을 때 그 그림의 사진 첨부 사본을 같은 대화에 만든다 | 작성자 | [ADR-20261120 / generated-image-draft](../../adr/ADR-20261120-generated-image-draft.md). 원고의 사진 판정, 두 지문, worker 의 사진 폴더 규칙을 바꾸지 않는다 |
| DECISION16 | 사본을 만드는 때 | 모델이 부르는 도구 `content_draft_image` 가 만든다 | 작성자 | 초안 미리보기(`render_draft`)가 원고 저장보다 먼저 돌아 파일 이름이 그때 필요하다. 원고 저장의 `assetId` 에 제목을 받으면 미리보기에서 그림을 보이지 못한다 |
| DECISION17 | 사본을 사진 목록에서 빼는 방법 | `chat_attachment` 의 새 칸 `source_artifact_id` 하나와 비운 `message_id` | 작성자 | 새 표를 만들지 않는다(사용자 결정 2026-11-01). 메시지에 묶지 않으면 사진 순번과 화면, 사진 참조 목록이 이미 빼므로 고칠 곳이 적다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| DECISION1, DECISION2 | architecture | [ADR-20261119 / generated-image](../../adr/ADR-20261119-generated-image.md) | 이 설계, [`fos-ctx` README](../../../hermes/plugins/fos-ctx/README.md) |
| DECISION3, DECISION4, DECISION5, DECISION6 | architecture | 필요 없음. 기존 결정(ADR-027, 새 표 금지)을 따르는 선택이다 | 이 설계 |
| DECISION7, DECISION8, DECISION9, DECISION10 | architecture | [ADR-20261119 / image-gen-defaults](../../adr/ADR-20261119-image-gen-defaults.md) | 이 설계의 「PR2」, [`agent-skill.md`](../agent-skill.md) 의 「에이전트 도구」 |
| DECISION11, DECISION12, DECISION14 | architecture | [ADR-20261120 / image-source](../../adr/ADR-20261120-image-source.md) | 이 설계의 「PR3」, [`fos-ctx` README](../../../hermes/plugins/fos-ctx/README.md) |
| DECISION13 | architecture | 필요 없음. 기존 첨부 번호와 순번 규칙([`attachment.md`](../attachment.md))을 따른다 | 이 설계의 「PR3」 |
| DECISION15, DECISION16, DECISION17 | architecture | [ADR-20261120 / generated-image-draft](../../adr/ADR-20261120-generated-image-draft.md) | 이 설계의 「PR4」, [`content-draft.md`](../content-draft.md) |
