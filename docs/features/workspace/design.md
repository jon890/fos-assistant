# 파일 공간 기술 설계

스펙 [`spec.md`](spec.md) 의 PR1(REQ1~REQ8)을 설계한다. PR2 부터의 요구사항은 그 PR 을 시작할 때 이 문서에 절을 더한다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | Control Plane MCP 서버에 `file_space_put` 도구를 더한다. 에이전트는 경로만 넘기고, Control Plane 이 읽기 전용으로 붙인 실행 공간이나 그 대화의 결과물 폴더에서 바이트를 읽어 요청자의 보관함에 암호화해 넣는다 |
| REQ2 | 주인과 대화는 서명한 `_fos_ctx` 의 origin 실행에서 정한다. 읽고 지우는 길은 기존 보관함의 주인 확인을 그대로 쓴다 |
| REQ3 | 도구가 넣은 보관 행에 표시(`vault_origin`)를 두고, turn 이 끝날 때 그 대화의 아직 묶이지 않은 행에 답 메시지 번호(`vault_message_id`)를 적는다. 메시지 응답이 이 행을 `FILE` 종류의 결과물로 내보내고 화면이 파일 줄로 그린다 |
| REQ4 | 보관함이 받는 형식을 `VaultFileType` 한 곳이 정한다. 확장자로 종류를 정하고, 이진 형식은 파일 머리 바이트를 함께 본다 |
| REQ5 | `assistant.artifact-vault.max-file-bytes` 의 기본값을 25MiB 로 올린다. 넣기 도구, 자동 보관, 「보관」 단추가 같은 값을 쓴다 |
| REQ6 | 제목과 설명을 `chat_artifact` 의 새 칸 `vault_meta` 에 주인의 데이터 key 로 암호화해 둔다. `artifact_write` 가 받은 제목은 그 경로의 결과물 행에 두었다가 보관할 때 보관 행으로 옮긴다 |
| REQ7 | `ArtifactService.agentPreamble` 에 「파일 공간」 단락을 더하고, 도구 설명에 같은 뜻을 적는다. 같은 대화·경로·본문은 기존 지문 규칙으로 한 건이 되고 도구가 이미 있다고 알린다 |
| REQ8 | 도구의 실패 결과가 넣지 못했다는 사실과 까닭, 넣었다고 답하지 말라는 말을 담는다. 파일 줄은 보관 행에서만 만든다 |
| REQ9 | PR2 가 설계한다. 방향은 `/files` 의 탭을 없애고 보관함 목록을 먼저 보이는 것이다 |
| REQ10 | PR2 가 설계한다. 방향은 보관함 목록을 번호 순 cursor 로 나눠 읽고 화면이 날짜 구분을 그리는 것이다 |
| REQ11 | PR2 가 설계한다. 종류는 이 PR 의 `VaultFileType` 이 정한 값을 쓴다 |
| REQ12 | PR2 가 설계한다. 방향은 그 사용자의 제목, 설명, 대화 제목을 풀어 메모리에서 찾는 것이다 |
| REQ13 | PR2 가 설계한다. 보관 행의 `conversation_id` 로 대화를 찾는다 |
| REQ14 | PR2 가 설계한다. 이 PR 은 새 형식을 내려받기로만 준다 |
| REQ15 | PR2 가 설계한다. 지우기 규칙은 이 PR 이 바꾸지 않는다 |
| REQ16 | PR2 가 설계한다 |
| REQ17 | PR2 가 설계한다 |
| REQ18 | PR2 가 설계한다 |
| REQ19 | PR3 이 설계한다. 방향은 MCP 도구 `file_space_search` 다 |
| REQ20 | PR3 이 설계한다 |
| REQ21 | PR3 이 설계한다. 이 PR 의 실행 공간 읽기와 `vault_origin` 을 다시 쓴다 |

## 책임과 경계

| 모듈 | 맡는 것 |
| --- | --- |
| `backend` 의 `mcp` | `file_space_put` 의 인자 검사, origin 실행에서 요청자·대화·에이전트 정하기, 경로가 실행 공간인지 대화 폴더인지 나누기, 결과 문구 |
| `backend` 의 `workspace` | 실행 공간 주인 키와 상대 경로로 파일 하나를 링크를 따라가지 않고 읽기. `mcp` 가 정한 포트를 구현한다 |
| `backend` 의 `chat.artifact` | 받는 형식과 크기 검사, 암호화해 보관 행으로 넣기, 제목과 설명, 답에 묶기, 메시지 응답 |
| `web` | 답 아래의 파일 줄, 보관함 목록의 제목 |

**바이트는 Control Plane 이 읽는다.** 에이전트의 작업 폴더는 Hermes 프로세스가 아니라 사용자별 Docker 실행 공간 안에 있고([ADR-086](../../adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md)), Control Plane 은 그 루트를 이미 읽기 전용으로 붙여 「실행 공간」 화면에 쓴다([ADR-20261009 / workspace-explorer](../../adr/ADR-20261009-workspace-explorer.md)).
그래서 모델이 본문을 인자로 옮기지 않고, Hermes 플러그인도 고치지 않는다. 근거와 기각한 대안은 [ADR-20261119 / file-space-put](../../adr/ADR-20261119-file-space-put.md) 이 갖는다.

**의존 방향.** 최상위 패키지의 층 순서에서 `workspace` 는 `mcp` 위에 있다(`TopLevelPackageOrder`). `mcp` 는 `workspace` 를 부르지 못하므로 `mcp.application.port.SandboxFiles` 포트를 두고 `workspace` 가 구현한다. `mcp` 가 이미 쓰는 포트 방식과 같다.

**요청 본문이 사용자나 대화를 정하지 못한다.** 도구에는 대화 식별자 인자가 없다. `McpCaller` 의 origin 실행에서 사용자와 대화를 읽는다. 다른 에이전트에게 맡긴 실행도 같은 길로 부모 대화에 닿는다.
실행 공간의 주인 키는 origin 실행의 에이전트에서 `Agent.sandboxOwner()` 로 만든다.

## 인터페이스와 데이터

### `file_space_put` 도구

| 인자 | 뜻 |
| --- | --- |
| `path` | 넣을 파일. 아래 순서로 판정한다. 먼저 이 대화의 결과물 폴더 경로(`ArtifactStore.agentFolder` 에 `/` 를 붙인 것)로 시작하면 그 뒤를 폴더 안의 상대 경로로 본다. 다음으로 `/workspace/` 로 시작하면 실행 공간 안의 파일이다. 그 밖의 절대 경로(다른 대화의 폴더 포함)는 `NOT_FOUND` 다. 상대 경로는 이 대화의 결과물 폴더 안의 파일이다(`artifact_write` 의 `path` 와 같은 뜻) |
| `title` | 선택. 짧은 한국어 제목. 200자까지 |
| `description` | 선택. 한 줄 설명. 500자까지 |

| 판정 | 결과 |
| --- | --- |
| 넣었다 | `{"saved": true, "already": false, "title", "message"}` |
| 같은 대화·경로·본문의 살아 있는 보관본이 있다 | `{"saved": true, "already": true, ...}`. 새로 만들지 않는다. 그 보관본이 넣기 도구로 넣은 것이면 `vault_message_id` 를 비워 이번 답에 다시 묶는다. 다시 생성한 답에서도 파일 줄이 새 답에 보이게 한다. 옛 답의 줄은 사라진다 |
| 받지 않는 형식, 머리 바이트가 형식과 맞지 않음 | `isError`, 까닭 `UNSUPPORTED_TYPE` |
| 파일 하나나 묶음의 상한을 넘음 | `isError`, 까닭 `TOO_LARGE` |
| 사용자 용량을 넘음 | `isError`, 까닭 `QUOTA_FULL` |
| 파일이 없음, 폴더나 링크임, 읽을 수 없음, 경로 형식이 틀림, 묶음이 부르는 파일이 빠짐, origin 실행에 에이전트가 없음 | `isError`, 까닭 `NOT_FOUND`. 실행 공간이 없는 에이전트는 주인 디렉터리나 파일이 없어 여기로 온다 |
| 동시에 도는 보관이 꽉 참, 암호화를 쓸 수 없음, 그 밖의 실패 | `isError`, 까닭 `UNAVAILABLE`. 글에 잠시 뒤 다시 하라는 말을 담는다 |

실패 결과의 글은 「파일 공간에 넣지 못했다」 로 시작하고 까닭과 「사용자에게 넣었다고 말하지 않는다」 를 담는다. 호스트 경로와 내부 번호는 담지 않는다.
대화나 사용자가 없는 origin 실행은 기존 `invalidContext` 결과다.
보관 서비스의 거절은 지금 대부분 `ARTIFACT_VAULT_UNSUPPORTED` 한 코드다. 도구는 `artifact_write` 가 하는 것처럼 예외의 고정 메시지로 까닭을 나눈다. 자동 보관의 오류 코드(`auto_vault_error`)와 화면 문구는 바꾸지 않는다.
도구의 넣기는 보관 자리가 비기를 10초까지 기다린다. 자동 보관이 도는 동안 바로 거절되지 않게 한다. 「보관」 단추와 자동 보관은 지금처럼 기다리지 않는다.

먼저 살펴보기 트리에서는 `artifact_write` 와 같이 쓰기 도구를 허용한 점검에서만 받는다(스펙 DECISION21). `McpController.writeAllowedInCheck` 는 인자의 `conversation_id` 를 점검 대화와 대조하는데 이 도구에는 그 인자가 없다. 이 도구는 origin 실행의 대화 번호가 점검 대화와 같은지로 대조한다.
웹의 도구 이름표(`web/src/lib/tool-label.ts`)에 이 도구를 더한다. fos-ctx 의 `REQUIRED_TOOLS` 는 고치지 않는다. 서명이 없는 호출은 Control Plane 이 `invalidContext` 로 거절한다.
`McpMemoryRemember.INTERNAL_TOOLS` 에 더한다. 이 도구의 결과에는 바깥 글이 없다.

### 받는 형식

`chat.artifact.domain.VaultFileType` 이 확장자에서 종류와 내줄 형식을 정한다.

| 종류 | 확장자 | 머리 바이트 검사 |
| --- | --- | --- |
| `IMAGE` | png, jpg, jpeg, gif, webp | 한다 |
| `DOCUMENT` | html, htm, css, md, txt | 하지 않는다. NUL 바이트가 있으면 거절한다 |
| `DOCUMENT` | pdf | `%PDF-` |
| `DOCUMENT` | docx, pptx | zip 머리(`PK`) |
| `TABLE` | csv, tsv | 하지 않는다. NUL 바이트가 있으면 거절한다 |
| `TABLE` | xlsx | zip 머리(`PK`) |

대화 폴더에서 온 HTML, CSS, 사진은 지금처럼 `ArtifactBundleReader` 가 부르는 파일까지 묶음으로 훑는다. 그 밖의 형식과 실행 공간에서 온 파일은 파일 하나를 바이트로 받아 넣는다. 실행 공간의 HTML 은 부르는 파일을 따라가지 않고 한 파일로 넣는다.
대화 폴더의 새 형식(엑셀, PDF 등)은 `ArtifactStore` 에 형식을 보지 않는 읽기를 따로 두어 읽는다. 폴더 안인지 보는 규칙은 기존 `ArtifactPathPolicy` 그대로다(실제 경로가 그 대화 폴더 안이어야 한다). 대화 폴더가 화면에 내주는 형식(`CONTENT_TYPES`)은 넓히지 않는다.

`VaultFileType` 을 쓰는 자리는 셋이다. 바이트로 받는 보관의 입구 검사(`ArtifactVaultService.keep`), 보관본을 내줄 때의 형식(`ArtifactVaultService.content`), 내줄 때 `attachment` 를 강제할지(`ArtifactVaultController.body`)다. 「보관」 단추와 자동 보관이 받는 형식은 지금처럼 대화 폴더가 내주는 형식이고, 파일 하나 상한만 함께 오른다.
이 PR 에서 새 형식의 본문은 `download` 값과 상관없이 `application/octet-stream` 과 `attachment` 로만 준다. 미리보기 형식은 PR2 가 정한다.

### 데이터

`chat_artifact` 에 칸 넷을 더한다. 새 표는 만들지 않는다. 칸의 정의는 마이그레이션과 [`data-schema.md`](../../../backend/docs/data-schema.md) 의 `chat_artifact` 절이 갖는다.

| 칸 | 뜻 |
| --- | --- |
| `vault_meta` | 제목과 설명의 JSON(`{"title", "description"}`)을 주인의 데이터 key 로 암호화한 글. 보관 뿌리 행과, `artifact_write` 가 제목을 받은 결과물 행에 있다 |
| `vault_meta_key_id` | 그 key 번호 |
| `vault_origin` | 보관 행이 들어온 길. `PUT` 은 넣기 도구가 대화 폴더에서, `PUT_WORKSPACE` 는 넣기 도구가 실행 공간에서 넣은 것이다. 비어 있으면 자동 보관이나 「보관」 단추다. PR3 이 값을 더한다 |
| `vault_message_id` | 넣기 도구가 넣은 보관 뿌리 행을 내준 답 메시지. turn 이 끝날 때 채운다 |

- 암호문의 자리는 사용자, 대화 번호, 경로로 묶는다. 결과물 행의 암호문을 같은 대화·경로의 보관 행으로 그대로 옮길 수 있다. 암호화를 쓸 수 없으면 본문 보관이 이미 거절되므로 평문으로 두는 길은 없다. `artifact_write` 쪽은 암호화를 쓸 수 없으면 제목을 버리고 파일만 쓴다
- 실행 공간에서 온 파일의 `path` 는 `/workspace/` 뒤의 상대 경로다. 대화 폴더의 같은 상대 경로와 섞이지 않게 둘을 `vault_origin` 으로 구분한다. 지문은 `/workspace/` 를 붙인 경로로 만들어 대화 폴더의 같은 경로·본문과 다른 건이 되게 한다. 대화 폴더의 경로로 보관본을 찾는 쿼리(`findLiveVaultCopies`)는 `PUT_WORKSPACE` 행을 뺀다
- 제목이 같은 대화에서 그 사용자의 살아 있는 보관 뿌리 행의 제목과 겹치면 `<제목> 2`, `<제목> 3` 을 차례로 본다. 사용자별 잠금 안에서 정한다. 같은 경로와 같은 들어온 길의 앞 보관본은 겹침에서 뺀다. 같은 파일을 고쳐 다시 보관할 때마다 번호가 늘지 않게 한다. 제목이 없으면 `vault_meta` 를 비워 두고, 읽는 쪽이 지금 목록이 보이는 이름을 쓴다
- 보관본을 지우면(`forgetVaulted`) 뿌리 행의 `vault_meta` 와 key 번호도 비운다. `vault_origin` 과 `vault_message_id` 는 남겨 답 아래 줄이 지운 파일이라고 보이게 한다. 지운 것을 도구로 다시 넣으면 이력 행을 지우고 새로 만들므로 옛 답의 「지운 파일」 줄은 사라지고 새 답에 줄이 생긴다
- **보관 본문을 읽지 않는 조회.** `vault_body` 는 지연 로딩이 아닌 `LONGTEXT` 다. 25MiB 로 올리면 엔티티로 읽는 조회마다 약 34MB 가 따라온다. 새 조회(답별 파일 줄, 제목 겹침, 제목을 가진 결과물 행)와 기존의 `findLiveVaultCopies`, `findByVaultUserIdAndVaultFingerprint` 를 본문을 뺀 projection 으로 한다

### `artifact_write` 의 제목

`title` 과 `description` 을 선택 인자로 더한다. HTML 경로일 때만 쓴다.
쓰기가 성공하면 그 대화·경로의 「답에 묶이지 않은 결과물 행」 에 `vault_meta` 를 둔다. 없으면 만들고 있으면 바꾼다.
`ArtifactService.recordTurn` 은 바뀐 HTML 을 묶을 때 그 경로의 묶이지 않은 행이 있으면 그 행에 답 번호와 크기를 적고, 없으면 지금처럼 새 행을 만든다.
보관(`ArtifactVaultService.vault`)은 그 대화·경로의 결과물 행 가운데 `vault_meta` 가 있는 가장 새 행의 값을 보관 뿌리 행으로 옮긴다. 파일이 지워진 행도 후보다. 자동 보관과 「보관」 단추가 같은 길을 지난다.
제목 없이 같은 경로를 다시 쓰면 앞의 제목을 그대로 쓴다. 제목을 주면 묶이지 않은 행의 값을 바꾼다.
묶이지 않은 결과물 행은 지금 그림 행뿐이고 쿼리가 `generationCallKey` 로 구분한다. 새 행은 `generationCallKey` 가 비고 `vault_meta` 가 있는 것으로 구분한다. 보관 기간 정리와 대화 삭제의 쿼리는 이 행을 다른 결과물 행과 같이 다룬다.

### 답에 묶기와 메시지 응답

`recordTurn` 이 그 대화에서 넣기 도구가 넣었고(`PUT`, `PUT_WORKSPACE`) `vault_message_id` 가 비었으며 지워지지 않은 보관 뿌리 행에 답 번호를 적는다. 그림 묶기와 같이 HTML 훑기의 결과나 실패와 상관없이 돌고, 실패해도 밖으로 던지지 않는다. 바뀐 HTML 이 없는 turn 에서도 묶여야 한다. turn 시작 시각으로 자르지 않는다. 맡긴 실행이 넣은 파일은 결과를 전하는 다음 답에 묶인다(이미지 기능과 같은 규칙).
그 답에 같은 경로의 결과물 행(HTML, 그림)이 있으면 대화 폴더에서 넣은(`PUT`) 보관 행은 묶지 않고 `vault_origin` 을 비운다. 같은 파일이 답 아래에 두 줄로 보이지 않게 한다. HTML 을 묶은 뒤에 본다.

`ChatDtos.ArtifactView` 에 필드 `title` 을 더하고 `kind` 에 `FILE` 을 더한다. 옛 화면은 모르는 필드를 무시한다.

| 필드 | `FILE` 일 때 |
| --- | --- |
| `path` | 보관 행의 경로. 지웠으면 빈 글 |
| `byteSize` | 묶음 전체의 크기 |
| `deleted` | 「내 파일」 에서 지웠다 |
| `title` | 제목. 없으면 null 이고 화면이 파일 이름을 쓴다 |
| `vaultItemId` | 보관 뿌리 행 번호. 내려받기와 「내 파일에서 보기」 에 쓴다 |

보관함 목록(`VaultItemView`)에 `title`, `description`, `kind` 를 더한다.

### 실행 입력의 단락

`agentPreamble` 의 결과물 폴더 단락 뒤에 아래 뜻의 줄을 더한다.

- 「파일 공간」(「내 파일」)은 이 서비스가 사용자에게 준 파일을 모아 두는 화면이다. 답에 묶인 HTML 과 `image_generate` 로 만든 그림은 자동으로 들어간다
- 아래 줄은 `file_space_put` 도구가 있을 때만 따른다고 먼저 적는다
- 사용자에게 줄 파일(엑셀, PDF, 문서, CSV 등)을 만들었거나 사용자가 파일 공간에 넣어 달라고 하면 `file_space_put` 으로 넣는다. 짧은 한국어 제목과 한 줄 설명을 붙인다
- 이미 들어 있는 것(자동으로 들어가는 HTML 과 그림, 결과가 `already` 인 것)은 다시 넣지 않고 이미 있다고 답한다
- 도구가 실패하면 넣었다고 답하지 않고 까닭을 알린다

## 실패와 일관성

- **넣기와 용량 확인은 기존 사용자별 잠금 안의 한 트랜잭션이다.** 동시에 도는 보관 자리(2개)도 그대로 쓴다. 파일 하나를 통째로 메모리에 올리므로 25MiB 에서 보관 하나가 쓰는 메모리는 평문, 암호문, base64 글을 더해 100MiB 안팎이다
- **실행 공간 파일은 한 번 읽은 바이트로 지문을 만들고 그대로 암호화한다.** 다시 읽지 않으므로 읽는 사이에 바뀌어도 넣은 것과 지문이 어긋나지 않는다. 크기는 열기 전과 읽은 뒤에 모두 본다. 링크와 하드 링크, 특수 파일은 기존 `WorkspaceTree` 규칙대로 열지 않는다
- **DB 가 한 번에 받는 크기.** 25MiB 파일의 암호문은 약 34MB 다. MySQL 의 `max_allowed_packet` 기본값(64MiB) 안이지만 운영 값은 이 저장소에서 알 수 없다. 배포 전에 운영 저장소에서 확인하고, 작으면 `assistant.artifact-vault.max-file-bytes` 를 운영 설정에서 낮춘다
- **맡긴 실행이 만든 파일.** 실행 공간은 에이전트 주인마다 다르다. 부모와 자식 에이전트의 주인이 다르면 부모가 자식의 `/workspace/` 파일을 읽지 못한다. 지침에 파일을 만든 에이전트가 직접 넣는다고 적는다
- **그룹에 공개한 에이전트.** 넣는 파일은 에이전트 주인의 실행 공간에서 읽고 요청자의 보관함에 넣는다. 요청자가 에이전트에게 주인의 작업 폴더 파일을 넣게 할 수 있다. 그 에이전트의 셸이 이미 같은 파일을 읽어 답에 옮길 수 있어 새 경계를 넘는 것은 아니다. ADR 의 「감당할 것」 에 적는다
- **되풀이 호출.** 같은 대화·경로·본문은 지문의 유일 인덱스로 한 건이다. 사용자가 지운 보관본을 도구로 다시 넣으면 「보관」 단추와 같이 지우기 이력을 지우고 새로 만든다(스펙 DECISION25)
- **답이 없는 turn.** 묶이지 않은 보관 행은 그 대화의 다음 답에 묶인다. 파일은 그 전에도 「내 파일」 에 있다
- **배포 순서.** backend 가 먼저 나가도 옛 화면은 `FILE` 줄을 HTML 줄로 그리지 않도록 backend 와 web 을 한 배포로 낸다. Hermes 쪽 변경은 없다. MCP 도구 목록은 Hermes 가 서버에서 읽으므로 profile 설정을 바꾸지 않는다. 도구 목록이 Hermes 에 다시 읽히는 때는 배포 뒤 운영 왕복에서 확인한다
- **이미지 기능 PR2 와의 겹침.** 그 PR 은 에이전트 도구 설정과 도구 판정을 고치고 보관함 코드를 고치지 않는다. 겹치는 것은 마이그레이션 번호와 `application.yml` 뿐이다. 번호는 `orch queue reserve` 로 받는다

## 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ1, REQ2, REQ4, REQ5, REQ8 | `/mcp` 를 서명한 호출로 부르는 통합 테스트(H2, 임시 실행 공간과 대화 폴더). 엑셀 넣기와 바이트 일치, 그룹 공개 에이전트의 주인, 형식·크기·용량·없는 파일의 실패 결과, 링크 거절, 살펴보기 트리 |
| REQ3 | `recordTurn` 뒤 메시지 응답의 `FILE` 항목, 지운 뒤의 `deleted`, 같은 경로 HTML 과 겹칠 때 한 줄, 넣기 도구 없이 대화 폴더에 쓴 엑셀이 묶이지 않음. 화면은 브라우저 테스트(Playwright)로 파일 줄과 지운 안내를 본다 |
| REQ6 | 제목 겹침의 번호, 제목 없는 파일, `artifact_write` 제목이 자동 보관본으로 옮겨짐, 목록 응답의 제목과 설명, 암호문이 평문 제목을 담지 않음 |
| REQ7 | 실행 입력에 단락이 있음, 같은 파일을 두 번 넣어 한 건과 `already` |
| 스키마 | Draft CI 의 `backend-mysql` 이 마이그레이션과 쿼리를 MySQL 에서 본다 |
| 운영 | 배포 뒤 실제 에이전트로 「내 파일 공간에 가져다 놔 줘」 를 한 번 왕복한다. 절차는 운영 저장소가 갖는다 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 바이트를 옮기는 주체 | Control Plane 이 읽기 전용으로 붙인 실행 공간과 대화 폴더에서 읽는다 | 작성자 | 작업 폴더는 Docker 실행 공간 안이라 Hermes 플러그인이 닿지 않고, Control Plane 은 이미 그 루트를 붙여 쓴다. [ADR-20261119 / file-space-put](../../adr/ADR-20261119-file-space-put.md) |
| DECISION2 | 도구의 자리 | Control Plane MCP 서버의 도구 하나 | 작성자 | Control Plane 이 여는 도구는 모두 그 서버에 둔다([`mcp.md`](../mcp.md)). profile 설정을 바꾸지 않고 MCP 가 붙은 에이전트가 모두 받는다(스펙 DECISION26) |
| DECISION3 | 대화 쪽 사본 | 두지 않는다. 답 아래 줄은 보관 행을 가리킨다 | 작성자 | 스펙 DECISION19 |
| DECISION4 | 제목과 설명을 두는 곳 | `chat_artifact` 의 암호화한 JSON 칸 하나 | 작성자 | 새 표를 만들지 않는다(2026-11-01 사용자 결정). 제목은 대화 내용을 드러낼 수 있어 본문과 같은 key 로 암호화한다 |
| DECISION5 | `artifact_write` 의 제목을 두는 곳 | 답에 묶이지 않은 결과물 행 | 작성자 | 이미지 기능이 그림 행을 올릴 때 만들고 turn 끝에 묶는 것과 같은 모양이다. 메모리에만 두면 재시작에 사라진다 |
| DECISION6 | 새 형식을 내주는 방식 | 이 PR 은 내려받기로만 준다 | 작성자 | 미리보기 화면은 PR2 의 범위다(스펙 REQ14) |
