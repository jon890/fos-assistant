# Gmail 커넥터

사용자의 Gmail 계정에 붙는 범용 커넥터다. 코드는 [`hermes/connectors/gmail/`](../../hermes/connectors/gmail) 에 있다.
이 문서는 도구와 정책, 보안, 설정 안내, 실제 계정으로 확인하는 절차를 갖는다.
결정의 근거는 [ADR-066](../adr/ADR-066-gmail-커넥터는-직접-만든-mcp-서버와-gmail-modify-scope-하나로-돌고-휴지통은-서버가-막는다.md) 에 있다.
커넥터 공통 계약은 [커넥터 연결](../connectors.md) 과 [커넥터 도구 정책](../backend/connector-tool-policy.md) 이 갖는다.

## 등록 칸

| 칸 | env | 비밀 | 무엇 |
| --- | --- | --- | --- |
| `client_id` | `GMAIL_OAUTH_CLIENT_ID` | 아니다 | 사용자가 만든 OAuth client 의 ID. `.apps.googleusercontent.com` 으로 끝나는 값만 받는다 |
| `client_secret` | `GMAIL_OAUTH_CLIENT_SECRET` | 그렇다 | 그 client 의 secret |
| `refresh_token` | `GMAIL_OAUTH_REFRESH_TOKEN` | 그렇다 | 그 client 로 받은 자기 계정의 refresh token |

셋 다 필수다. 운영자가 주는 값(`operator_env`)은 없다.
확인 도구는 `get_profile` 이다. 등록할 때 세 값으로 계정 주소를 읽어 보고, 읽히면 저장한다.

## 도구와 정책

MCP 서버 이름은 `gmail` 이다. 인자는 `search_messages` 의 `max_results`(정수)를 빼고 모두 글자다. 승인 카드가 인자를 키와 값으로 그대로 보이기 때문이다.

| 도구 | 위험도 | 승인 | 상시 허락 | 카드 제목 | 하는 일 |
| --- | --- | --- | --- | --- | --- |
| `get_profile` | `READ` | 없음 | | | 계정 주소와 메일 수를 읽는다 |
| `list_labels` | `READ` | 없음 | | | 라벨의 이름과 종류를 읽는다 |
| `search_messages` | `READ` | 없음 | | | Gmail 검색 문법으로 메일을 찾는다 |
| `get_message` | `READ` | 없음 | | | 메일 하나의 머리와 본문을 읽는다 |
| `get_thread` | `READ` | 없음 | | | 스레드의 메일을 차례로 읽는다 |
| `create_draft` | `WRITE` | 필요 | 줄 수 있다 | 초안 만들기 | 초안함에 초안을 만든다. 보내지 않는다 |
| `modify_labels` | `WRITE` | 필요 | 닫았다 | 라벨 바꾸기 | 메일 하나에 라벨을 더하고 뗀다. 보관과 읽음 처리가 여기 든다 |
| `send_message` | `WRITE` | 필요 | 닫았다 | 메일 보내기 | 새 메일을 보낸다 |
| `reply_to_message` | `WRITE` | 필요 | 닫았다 | 답장 보내기 | 받은 메일의 스레드에 답장을 보낸다 |

분류의 까닭이다.

- 읽기 다섯은 계정의 상태를 바꾸지 않는다. 메일 본문은 민감하지만 이 커넥터를 연결한 뜻이 메일을 읽는 것이라 호출마다 묻지 않는다. 읽은 글은 그 사용자의 대화에만 간다.
- 초안은 계정 안에서 되돌릴 수 있는 쓰기다. 밖으로 나가지 않으므로 상시 허락을 줄 수 있다.
- 라벨 바꾸기도 계정 안의 쓰기지만 상시 허락을 닫았다. 속은 모델이 보안 알림 메일을 보관하거나 읽음 처리해 주인의 눈에서 숨길 수 있기 때문이다.
- 밖으로 나간다는 선언(`"outbound": true`)은 보내기와 답장 둘이다.
- 보내기와 답장은 계정 밖의 사람에게 나간다. `"grant": false` 로 상시 허락을 닫아 호출마다 사람이 받는 사람과 제목과 본문을 본다([ADR-065](../adr/ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md)).
- 휴지통과 영구 삭제는 도구가 없다. 선언에 없는 도구는 거절된다.

### 도구의 인자와 결과

| 도구 | 인자 | 결과 |
| --- | --- | --- |
| `get_profile` | 없음 | `{email, messages_total, threads_total}` |
| `list_labels` | 없음 | `{labels: [{id, name, type}]}`. `type` 은 `system` 이나 `user` |
| `search_messages` | `query`(Gmail 검색 문법, 비우면 최근 메일), `max_results`(1~25, 기본 10), `page_token` | `{messages: [{id, thread_id, from, to, subject, date, snippet, labels}], next_page_token}` |
| `get_message` | `message_id` | `{id, thread_id, from, to, cc, subject, date, labels, body, body_truncated, attachments: [{filename, mime_type, size}]}` |
| `get_thread` | `thread_id` | `{id, messages: [get_message 와 같은 모양], messages_truncated}` |
| `create_draft` | `to`, `subject`, `body`, `cc`, `bcc`, `reply_to_message_id` | `{draft_id, message_id, thread_id}` |
| `modify_labels` | `message_id`, `add_labels`, `remove_labels` | `{id, labels}` |
| `send_message` | `to`, `subject`, `body`, `cc`, `bcc` | `{id, thread_id}` |
| `reply_to_message` | `message_id`, `to`, `subject`, `body`, `cc` | `{id, thread_id}` |

- **받는 사람(`to`, `cc`, `bcc`)은 쉼표로 나눈 주소만 받는다. 표시 이름을 받지 않는다.** `a@example.com, b@example.net` 모양이다. 카드에 보이는 글과 실제 받는 주소가 다르게 읽힐 길을 없앤다. 주소 하나는 ASCII 영문자, 숫자, `.!#$%&'*+/=?^_{|}~-` 로 된 이름과 `@` 와 점으로 이은 도메인이다. 그 밖의 모양은 Gmail 을 부르지 않고 `GMAIL_INVALID_INPUT` 으로 거절한다. `이름 <주소>`, 따옴표, 괄호 주석, 꺾쇠, `:` 와 `;` 가 든 그룹 문법, ASCII 밖 글자, 인코딩된 낱말(`=?utf-8?b?...?=`)이 모두 여기 든다
- 서버는 조립한 메일을 다시 읽어 `To`, `Cc`, `Bcc` 의 주소 목록이 인자의 주소 목록과 같은지 확인한다. 다르면 보내지 않고 `GMAIL_INVALID_INPUT` 이다
- 제목에 줄바꿈 같은 제어 문자, 화면에 보이지 않거나 글의 방향을 바꾸는 문자(Unicode 범주 Cf), 앞 글자에 붙어 아무것도 그리지 않는 결합 문자(범주 Mn, U+034F 와 변이 선택자 등), 인코딩된 낱말(`=?...?=`)이 있으면 거절한다. 이모지에 붙는 U+FE0F 만 기호 바로 뒤에서 받는다. 분해해 쓴 `é` 같은 글자는 한 글자로 합쳐 써야 한다. 본문도 Cf 문자를 거절하되 그림 글자를 잇는 U+200C 와 U+200D 는 받는다. 한글 채움 문자(U+3164)처럼 빈칸으로 보이는 글자도 제목과 본문에서 거절한다
- 답장의 `In-Reply-To` 와 `References` 에는 원래 메일의 `<...>` 모양 번호만 옮긴다. 다른 글이 섞인 머리는 버리고 스레드 번호만으로 답장한다
- 승인 카드는 받는 사람을 인자의 글 그대로 보인다. 주소만 받으므로 카드의 글이 곧 받는 주소다
- `to`, `subject`, `body` 는 비울 수 없다
- `add_labels` 와 `remove_labels` 는 쉼표로 나눈 라벨 이름이다. 시스템 라벨은 `INBOX`, `UNREAD`, `STARRED`, `IMPORTANT` 처럼 그 이름을 쓰고 사용자 라벨은 화면에 보이는 이름을 쓴다. 서버가 이름을 라벨 번호로 바꾼다. 모르는 이름은 `GMAIL_INVALID_INPUT` 이다. 보관은 `remove_labels: "INBOX"` 다
- **`add_labels` 나 `remove_labels` 에 `TRASH` 나 `SPAM` 이 있으면 Gmail 을 부르지 않고 `GMAIL_INVALID_INPUT` 으로 거절한다.** 휴지통과 스팸에 넣는 것도 꺼내는 것도 하지 않는다. 대소문자를 구분하지 않고, 이름이 그 라벨 번호로 바뀐 경우도 거절한다
- `reply_to_message` 는 받는 사람과 제목을 원래 메일에서 채우지 않는다. 인자로 받은 그대로 보낸다. 승인한 것과 보낸 것이 같아야 하기 때문이다. 서버는 원래 메일의 스레드와 `Message-ID` 를 읽어 `In-Reply-To` 와 `References` 머리만 채운다
- `create_draft` 의 `reply_to_message_id` 를 주면 그 메일의 스레드에 답장 초안을 만든다
- 본문은 글(`text/plain`)로만 보낸다. 첨부를 붙이는 인자는 없다
- `get_message` 의 `body` 는 `text/plain` 부분을 먼저 쓰고, 없으면 `text/html` 에서 태그를 뗀 글을 쓴다. 20,000자에서 자르고 잘랐으면 `body_truncated` 가 참이다. HTML 의 `script`, `style`, `head`, `title`, `template`, `noscript` 안의 글은 버린다. `</head>` 를 닫지 않은 메일은 `<body>` 에서 본문이 시작한다고 읽되, 다른 숨김 태그 안의 `<body>` 는 숨김을 풀지 않는다. 머리 값은 1,000자, 첨부 목록은 50개, 첨부 이름은 255자에서 자른다. Gmail 의 응답이 10MB 를 넘으면 `GMAIL_UNAVAILABLE` 이다. `get_thread` 는 메일마다 5,000자, 메일 20개까지다
- 첨부는 이름, 종류, 크기만 낸다. 내용을 읽는 도구는 없다
- 읽기 도구 셋(`search_messages`, `get_message`, `get_thread`)의 결과에는 `notice` 칸이 더 붙는다. 아래 「보안」 이 갖는다
- 결과의 `labels` 는 Gmail 의 라벨 번호다. 시스템 라벨은 번호가 이름과 같고, 사용자 라벨의 이름은 `list_labels` 로 찾는다
- `message_id`, `thread_id`, `reply_to_message_id` 는 영문자, 숫자, `_`, `-` 로 된 64자까지의 글만 받는다. 아니면 Gmail 을 부르지 않고 `GMAIL_INVALID_INPUT` 이다
- 도구는 프로세스 안의 상태에 기대지 않는다. access token 은 그 프로세스가 필요할 때 refresh token 으로 받는다

### 오류

| 서버가 내는 코드 | 공통 어휘 | 언제 |
| --- | --- | --- |
| `GMAIL_UNAUTHORIZED` | `credential_rejected` | 토큰 endpoint 가 `invalid_grant` 나 `invalid_client` 로 답했다. Gmail API 가 401 로 답했다. 세 값 가운데 하나가 비었다 |
| `GMAIL_FORBIDDEN` | `forbidden` | Gmail API 가 403 으로 답했다. scope 가 모자라거나 프로젝트에서 Gmail API 를 켜지 않았다 |
| `GMAIL_INVALID_INPUT` | `invalid_input` | 인자가 비었거나 모양이 틀리다. 막은 라벨을 더하려 했다. Gmail API 가 400 이나 404 로 답했다 |
| `GMAIL_SEND_UNKNOWN` | `outcome_unknown` | 보내기나 답장의 보내는 요청(`messages/send`)을 보낸 뒤 시간 안에 답이 없거나, 연결이 끊겼거나, 응답을 읽지 못했거나, Gmail API 가 5xx 로 답했다. 메일이 나갔는지 모른다. 승인 줄은 실패가 아니라 `UNKNOWN` 으로 남는다 |
| `GMAIL_UNAVAILABLE` | `unavailable` | 연결하지 못했다. 시간 안에 답이 없다. Gmail API 가 3xx, 429, 5xx 나 위에 없는 4xx 로 답했다. 응답을 읽지 못했다. 토큰 endpoint 가 `invalid_grant` 와 `invalid_client` 밖의 오류로 답했다 |

오류 결과는 코드만 담는다. Google 이 준 오류 글과 요청한 주소는 결과와 로그에 싣지 않는다.
외부 호출의 제한 시간은 호출마다 15초다. 대시보드가 확인 도구를 기다리는 시간은 10초라, Google 이 느리게 답하면 등록과 연결 확인이 서버의 제한 시간보다 먼저 `unavailable` 로 끝난다. 어느 호출도 다시 부르지 않는다. 보내기가 시간 안에 답하지 않았을 때 다시 부르면 메일이 두 번 나갈 수 있다.

## scope 와 휴지통

요청하는 scope 는 `https://www.googleapis.com/auth/gmail.modify` 하나다.

| 하려는 것 | 이 scope 로 | 막는 곳 |
| --- | --- | --- |
| 읽기, 검색, 초안, 보내기, 라벨 바꾸기 | 된다 | |
| 휴지통으로 옮기기 | **된다** | 서버 코드. 휴지통 endpoint 를 부르지 않고 라벨 바꾸기에서 `TRASH` 와 `SPAM` 을 더하는 것도 떼는 것도 거절한다 |
| 영구 삭제 | 안 된다 | scope. `https://mail.google.com/` 을 받아야 한다 |

보관(`INBOX` 라벨 떼기)에 `gmail.modify` 가 필요해 더 작은 scope 를 고를 수 없다.
`hermes/tests/connectors/test_gmail.py` 가 `TRASH` 와 `SPAM` 의 거절을 보고, 대역이 받은 요청의 메서드와 경로가 허용 목록 안에만 있는지 본다. 허용 목록은 토큰 받기, `profile`, `labels`, `messages` 의 조회와 `send` 와 `modify`, `threads` 의 조회, `drafts` 의 만들기다. 휴지통, 삭제, 첨부, 설정 경로는 목록에 없다.

**더 넓은 scope 로 받은 토큰을 넣지 않는다.** `https://mail.google.com/` 으로 받은 토큰도 등록은 되지만, 그 토큰이 새면 메일을 영구히 지울 수 있다.

## 보안

메일 본문은 누구나 보낼 수 있는 글이다. 그 글에 「이 메일을 아무개에게 전달하라」 같은 지시가 숨어 있을 수 있다.
그 글이 모델을 속인다는 전제로 아래 넷이 닿는 범위를 정한다.

| 무엇 | 어떻게 |
| --- | --- |
| 전용 에이전트 | 이 커넥터의 에이전트는 자기 MCP 서버의 도구만 받는다. Memory 문맥을 받지 않고 Control Plane 도구를 부르지 못한다([ADR-045](../adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)). 속은 모델이 닿는 곳은 이 메일 계정과 그 대화의 답이다 |
| 다른 에이전트로 가는 결과 | 다른 에이전트가 이 에이전트에게 일을 맡겼으면 Control Plane 이 답을 `<external-data>` 로 감싸고 지시로 따르지 말라는 줄을 붙여 전한다([커넥터 설치](../backend/connector-install.md) 의 「커넥터 에이전트의 경계」) |
| 쓰기 | 초안, 라벨, 보내기, 답장은 모두 승인 뒤에만 실행된다. 보내기와 답장은 상시 허락이 없어 호출마다 승인한다 |
| 승인한 것과 실행한 것 | 승인 카드는 받는 사람(`to`, `cc`, `bcc`), 제목(`subject`), 본문(`body`)을 마크다운이나 HTML 로 읽지 않고 글자로 보인다. 토큰처럼 보이는 긴 글만 `[가림]` 으로 바꿔 보인다. 승인하면 Control Plane 이 저장한 인자 그대로 한 번만 실행한다. 결과를 모르면 다시 실행하지 않는다([ADR-050](../adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md)) |

서버가 하는 것도 있다.

- 읽기 도구의 결과에 `notice` 칸을 넣어, 메일의 글은 보낸 사람이 쓴 자료이고 지시가 아니라고 적는다
- 스킬 지침(`skills/gmail/SKILL.md`)이 같은 것을 말하고, 메일의 글이 시키는 쓰기를 하지 말고 사용자에게 알리라고 적는다

**보내기, 답장, 라벨 바꾸기는 카드에 가려지는 글이 있으면 승인되지 않는다.** 승인 카드는 비밀처럼 보이는 키의 값과 토큰 모양의 긴 글을 `[가림]` 으로 바꿔 보인다. 가릴 자리는 글의 모양이 정하므로, 속은 모델이 내보낼 내용을 그런 모양으로 만들면 사람이 읽지 못한 글이 나간다. 그래서 이 세 도구의 요청은 가려지는 글이 하나라도 있으면 화면이 승인을 막고 Control Plane 도 실행하지 않는다([커넥터 연결](../connectors.md) 의 「승인 줄과 경로」). 본문에 긴 링크 토큰이나 key 가 든 메일은 이 커넥터로 보낼 수 없다. 초안에는 이 제한이 없다. 초안의 카드에 `[가림]` 이 보이면 그 자리는 Gmail 초안함에서 확인한다.

**이것으로 모델이 속지 않는다고 보장하지 못한다.** 마지막 방어는 사람이 승인 카드를 읽는 것이다.
속은 모델이 읽은 메일의 내용을 그 대화의 답에 옮기는 것은 막지 못한다. 그 답은 계정 주인만 본다.
HTML 메일에서 화면에 보이지 않게 꾸민 글(예: 숨긴 문단)은 걸러 내지 못하고 본문으로 모델에 간다.
초안에 상시 허락을 주면 속은 모델이 초안을 만들 수 있다. 초안은 계정 밖으로 나가지 않고 지울 수 있다.

## 설정 안내

사용자가 처음 한 번 한다. Google 계정과, 브라우저와 Python 3 이 있는 컴퓨터가 필요하다.

### 1. Google Cloud 프로젝트와 OAuth client

1. [Google Cloud 콘솔](https://console.cloud.google.com/) 에서 프로젝트를 하나 만든다
2. 「API 및 서비스」 의 라이브러리에서 **Gmail API** 를 찾아 사용 설정한다
3. 「OAuth 동의 화면」 을 만든다. 사용자 유형은 「외부」 를 고른다. Google Workspace 계정이고 자기 조직 안에서만 쓰면 「내부」 를 고른다
4. 데이터 액세스(scope)에 `https://www.googleapis.com/auth/gmail.modify` 를 더한다
5. **게시 상태를 「프로덕션」 으로 바꾼다.** 검수는 신청하지 않는다. 아래 「토큰이 만료되거나 철회됐을 때」 에 까닭이 있다
6. 「사용자 인증 정보」 에서 OAuth 클라이언트 ID 를 만든다. 애플리케이션 유형은 **데스크톱 앱** 이다
7. 만들어진 클라이언트 ID 와 클라이언트 보안 비밀번호(secret)를 적어 둔다

### 2. refresh token 받기

이 저장소의 스크립트를 자기 컴퓨터에서 돌린다. 표준 라이브러리만 쓴다.

```bash
# cwd: 저장소 root
python3 hermes/connectors/gmail/scripts/get_refresh_token.py
```

1. 스크립트가 클라이언트 ID 와 secret 을 묻는다. secret 은 입력하는 동안 화면에 보이지 않는다
2. 스크립트가 주소 하나를 출력한다. 브라우저로 그 주소를 열고 연결할 Google 계정으로 로그인한다
3. 「Google 에서 확인하지 않은 앱」 경고가 나오면 「고급」 을 눌러 계속한다. 자기가 만든 앱이라 나오는 경고다
4. 메일 권한을 허용하면 브라우저에 끝났다는 글이 보이고, 터미널에 refresh token 이 출력된다

스크립트는 자기 컴퓨터의 `127.0.0.1` 에 임시 포트를 열어 Google 이 돌려주는 코드를 받는다. 5분 안에 동의를 끝내지 않으면 스크립트가 끝나므로 다시 돌린다. 값을 파일에 쓰지 않는다.
출력된 refresh token 은 그 계정의 메일을 읽고 보낼 수 있는 값이다. 붙여 넣은 뒤 터미널 기록에서 지운다.

Python 을 쓸 수 없으면 [OAuth 2.0 Playground](https://developers.google.com/oauthplayground) 로 받을 수 있다.
이때는 클라이언트 유형을 「웹 애플리케이션」 으로 만들고 승인된 리디렉션 URI 에 Playground 의 주소를 넣은 뒤, Playground 설정에서 자기 클라이언트를 쓰도록 켠다.
이 방법은 client secret 을 Google 의 웹 도구에 입력한다.

### 3. 연결 화면에 넣기

1. 「연결」 화면에서 Gmail 을 고른다
2. 클라이언트 ID, 클라이언트 secret, refresh token 을 붙여 넣고 등록한다. 값이 맞으면 계정을 읽어 본 뒤 저장한다
3. 「연결 확인」 을 누른다. 상태가 준비됨이 되면 Gmail 에이전트로 대화를 시작한다
4. 값을 바꿔 다시 등록했거나 해제했으면 관리자가 반영을 끝낼 때까지 재시작 대기로 남는다

### 승인 카드에서

- 보내기와 답장은 호출마다 카드가 뜬다. 받는 사람과 본문을 읽고 승인한다
- 보내기, 답장, 라벨 바꾸기의 카드에 「가려진 내용이 있어 승인할 수 없어요」 가 보이면 거절하고, 에이전트에게 그 부분을 빼거나 풀어 써 달라고 한다
- 초안 만들기에만 「승인하고 묻지 않기」 가 있다. **초안은 상시 허락을 줘도 외부로 나가지 않는다.** 초안함에 남을 뿐이고 보내려면 따로 승인해야 한다

### 토큰이 만료되거나 철회됐을 때

| 까닭 | 일어나는 일 | 할 일 |
| --- | --- | --- |
| 동의 화면이 「테스트」 상태다 | refresh token 이 7일 뒤 만료된다 | 게시 상태를 「프로덕션」 으로 바꾸고 토큰을 다시 받는다 |
| Google 계정의 비밀번호를 바꿨다 | Gmail 권한이 든 토큰이 모두 무효가 된다 | 토큰을 다시 받는다 |
| 6개월 동안 쓰지 않았다 | 만료된다 | 토큰을 다시 받는다 |
| 같은 client 로 토큰을 여러 번 받았다 | 한도를 넘으면 오래된 것부터 무효가 된다 | 쓰는 토큰을 다시 받는다 |
| 사용자가 접근을 철회했다 | 바로 무효가 된다 | 다시 쓰려면 토큰을 다시 받는다 |

토큰이 죽으면 도구 호출이 자격 증명 거절로 끝나고 에이전트가 메일을 읽지 못했다고 답한다. 「연결 확인」 도 실패한다.
위 「2. refresh token 받기」 를 다시 하고 연결 화면에서 새 값으로 다시 등록한다.

**접근을 끊으려면** Google 계정의 「보안」 에서 「서드 파티 앱 및 서비스」 연결 목록을 열어 자기가 만든 앱을 지운다. 그 순간부터 토큰이 거절된다.
연결 화면의 해제는 이 비서 쪽의 값을 지운다. Google 쪽의 권한은 위 방법으로 따로 지운다.

## 실제 계정으로 확인하기

검사는 대역으로만 돈다. 실제 Gmail 로 도는지는 소유자가 배포한 뒤, 그리고 Gmail API 의 변경 공지가 있을 때 손으로 확인한다.
운영자가 커넥터를 목록에 올리는 방법은 [`hermes/README.md`](../../hermes/README.md) 의 「커넥터」 가 갖는다.

| 순서 | 하는 일 | 기대하는 것 |
| --- | --- | --- |
| 1 | 위 설정 안내대로 등록하고 연결 확인을 누른다 | 상태가 준비됨이다. 도구 목록에 아홉 개가 보이고 선언하지 않은 도구가 0 이다 |
| 2 | 「최근 받은 메일 세 개를 알려 줘」 | 승인 없이 제목과 보낸 사람이 나온다 |
| 3 | 그 가운데 하나를 읽어 달라고 한다 | 본문이 나온다. 한글 메일이 깨지지 않는다 |
| 4 | 자기 주소로 보낼 초안을 만들어 달라고 한다 | 승인 카드에 받는 사람, 제목, 본문이 보인다. 승인하면 Gmail 초안함에 있다 |
| 5 | 자기 주소로 메일을 보내 달라고 한다 | 카드에 「승인하고 묻지 않기」 가 없다. 승인하면 한 통만 도착한다 |
| 6 | 받은 메일에 답장을 보내 달라고 한다 | 승인한 뒤 Gmail 에서 같은 스레드에 붙어 있다 |
| 7 | 메일 하나를 보관해 달라고 한다 | 승인한 뒤 받은편지함에서 빠지고 전체보관함에 남는다 |
| 8 | 메일 하나를 지워 달라고 한다 | 지우지 못한다고 답한다. Gmail 휴지통에 그 메일이 없다 |
| 9 | Google 계정에서 앱 접근을 철회하고 메일을 물어본다 | 읽지 못했다고 답한다. 연결 확인이 실패한다 |
