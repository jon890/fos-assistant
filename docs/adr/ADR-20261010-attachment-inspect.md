## ADR-20261010 / attachment-inspect: 현재 실행이 같은 대화의 원본 사진을 native 도구 결과로 다시 읽는다

- **status**: `accepted`
- Date: 2026-10-10

### 결정

`fos-ctx`가 독립 도구 `attachment_inspect`를 공식 `register_tool`로 등록한다.
Control Plane은 `/internal/hermes/attachment-inspect`에서 profile 토큰과 실제 hook 호출의
`_fos_ctx`를 `McpCallerResolver`로 검증한다. 커넥터 격리 정책은 그대로 적용한다.
입력은 정수 `attachment_id`, 선택 `region=[x1,y1,x2,y2]` 또는 명시적 `overview=true`다. 경로와 URL은 받지 않는다.

새 profile 틀은 `fos-attachments`를 포함한다. 기존 개인 에이전트는 사진이 있는 일반 turn을
제출하기 전에 Control Plane이 이 toolset을 자동 적용하고 확인한다. 적용에 실패하면 실행 실패를 기록한다.
사용자 설정과 스킬 publish는 이미 적용된 내부 toolset을 보존한다. 공유 profile과 커넥터에는 자동 추가하지 않는다.

현재 origin이 `RUNNING`일 때만 읽는다. hook은 읽기 전용 `state.db`의 source와 부모 사슬에
`subagent`가 없음을 확인해 압축된 최상위 session을 증명한다. 그 `top_level`도 추가 서명에 넣는다.
증명한 최상위 사슬은 endpoint 전용 resolver 진입에서 안정된 correlation root의 현재 실행으로 찾는다.
따라서 과거 binding이 있어도 다음 turn에서 같은 대화의 과거 사진을 읽을 수 있다.
subagent 사슬은 최초 origin에서만 찾으므로 끝난 부모의 하위 session은 거절한다.
읽지 못한 사슬은 최상위로 추측하지 않는다. 다른 MCP 호출과 전역 session resolver는 바꾸지 않는다.

최상위 session은 여러 turn이 재사용하므로 기존 서명만으로는 옛 호출을 구별하지 못한다.
hook이 `_fos_inspect`를 덮어쓰며 발급 시각과 요청 digest를 추가로 서명한다.
서명할 UTF-8 글은 `v1-attachment-inspect`, root session, session, 실제 tool call,
`issued_at_ms`, 요청 digest, 최상위 증명 `1` 또는 `0`을 개행으로 잇는다. key는 기존 토큰 해시 문자열이다.
digest는 `attachment_id`와 region의 쉼표 구분 정수(없으면 빈 문자열)를 개행으로 이은 글의 SHA-256이다.
명시적 개요가 true이면 개행과 `overview=1`을 덧붙인다. 생략·false는 기존 digest다.
개요와 region은 함께 받지 않으며 fetch와 validate 모두 이 모드를 검증한다.
발급 시각은 현재 실행 시작 이후이며 서버 현재 시각 이전이고 60초 이내여야 한다.
따라서 과거 실행의 proof는 현재 실행에서도 쓰지 못한다. 두 서버의 시계는 동기화되어야 한다.
한 실행의 조회는 90회, 첨부별 3회, 같은 tool call은 2회로 제한한다.
30장 각각 전체와 영역 조회 60회를 허용하고 같은 사진의 반복을 막는다.
실행 시작 뒤 한 시간까지만 조회하며 서버 재시작은 카운터를 초기화한다.

대화 소유와 첨부 업로더를 확인하고, 보낸 메시지에 묶인 첨부만 읽는다.
지워졌거나 보관 기한을 지난 원본은 거절한다. 파일 접근은 기존 저장 record와 링크 방지 경계를 따른다.
처리 후에도 실행 상태와 첨부 상태를 다시 검사한다.

region은 EXIF 방향을 반영한 원본 표시 좌표이며 오른쪽·아래 끝은 제외한다.
먼저 해당 원본 영역만 decode하고 회전한다. 원본은 60M 픽셀, 조회 결과는 16M 픽셀,
입력은 20MiB, 출력은 10MiB, decode는 동시에 한 장으로 제한한다.
병렬 호출은 공정한 큐에서 15초까지 기다리고 기다리는 동안 실행 취소를 검사한다.
한도를 넘으면 축소 성공으로 숨기지 않고 영역을 지정해 다시 조회하도록 오류를 낸다.
JPEG/PNG는 원본 방향이 정상인 전체 조회에서 decode 검증 후 bytes를 그대로 돌려준다.
회전·crop 결과는 픽셀을 줄이지 않은 PNG다. 이때 투명 부분은 기존 사본 처리처럼 흰 배경으로 합성한다.
전체 PNG 원본을 그대로 돌려줄 때는 투명 픽셀도 그대로 보존한다.
GIF/WebP 원본은 CP가 decode하지 않고 최대 20MiB bytes로 반환한다. plugin의 기존 Pillow가 첫 표시 프레임을
EXIF 방향과 alpha를 보존한 PNG로 만든다. 움직임과 뒤 프레임을 확인하지 않았음을 native text와 fallback에 명시한다.
원본 파일은 다시 쓰지 않는다. WebP 최초 사본이 없어도 같은 모델의 도구 조회로 픽셀을 전달한다.

큰 전체 원본이 결과 한도를 넘고 지난 turn의 사본도 없으면 치수만으로 crop 위치를 고를 수 없다.
따라서 `overview=true`일 때 네 MIME 모두 CP가 검증한 원본을 helper로 넘겨 긴 변 1600픽셀 이하의
전체 개요를 만든다. 이 결과는 축소 개요임과 원본 표시 치수·실제 결과 치수를 명시하며 원본 성공으로 숨기지 않는다.
모델은 개요를 본 뒤 원본 표시 좌표의 region을 자동 요청한다. 작은 글자는 개요만으로 판독했다고 하지 않는다.
전체 실패→개요→crop 3회 또는 개요→crop 2회가 기존 예산 안에 들어간다.
JPEG의 공개 Pillow draft 최적화는 개요에서만 적용한다. 기본 JPEG/PNG 원본과 crop은 기존 Java 결과를 유지한다.

GIF 논리 화면·첫 descriptor와 WebP RIFF 길이·padding·VP8X canvas·VP8/VP8L bitstream·ANMF의
모든 frame 치수를 Pillow open 전에 검사한다. codec size와 사전 치수도 비교한다.
원본 60M·결과 16M·PNG 10MiB를 유지하며 큰 전체 결과는 안전한 표시 치수와 `region_required` 오류다.

profile namespace 밖의 plugin 소유 표준 모듈에 FIFO runtime 하나를 원자적으로 게시한다.
reload는 진행 중 runtime을 바꾸지 않으며 버전 변경은 프로세스 재시작으로 적용한다.
원본 수신 전에 차례를 확보해 대기 요청의 원본 배열 누적을 막는다. JPEG/PNG Java 슬롯과 lease로 묶지 않는다.
이 제한은 프로세스별이며 여러 gateway 프로세스의 슬롯 수와 머신 전체 메모리는 운영 확인 대상이다.

큐 15초를 포함한 handler 전체 30초 안에서 shell 없는 helper 프로세스와 제한된 익명 pipe를 감독한다.
Linux helper는 Pillow open 전에 주소 공간 1.5GiB를 적용한다. 60M은 원본 허용 최대치이며
모든 최대 원본의 변환 성공을 보장하지 않는다. 제한 불가 환경은 실패로 끝낸다.
부모 종료 SIGKILL과 프로세스 그룹 terminate·kill·wait 뒤 슬롯 반환을 적용한다.
합성 Linux 검사에서 crop 뒤 RGBA 변환으로 전체 복사를 줄인 48M·60M EXIF6 WebP crop이
1.5GiB 한도 안에서 성공했다. peak RSS는 각각 761.3MiB·944.7MiB였다.
이 수치는 모든 최대 원본의 처리 성공이나 실제 동시 부하의 메모리를 보장하지 않는다.

`POST /internal/hermes/attachment-inspect/validate`는 같은 서명·시각·Origin·ROLE_MCP 경계를 검사하고
현재 실행·루트 취소·대화·첨부 상태를 SQL로 확인한 뒤 204만 반환한다. 조회 예산을 소비하지 않고
bytes·새 proof·grant·lease를 만들지 않는다. 원 proof의 60초·실행 한 시간 기한을 연장하지 않는다.
개요는 대기부터, 기본 GIF/WebP는 MIME 수신부터 약 500ms마다 검증하고 종료 직전에도 검증해 관측된 삭제·만료·취소 결과를 버린다.
기본 JPEG/PNG는 기존 CP의 decode 전후 검증을 유지하므로 옛 CP에서도 동작한다.
마지막 검증과 provider 전송 사이의 짧은 경쟁과 이미 보낸 픽셀의 회수는 이 HTTP 경계가 보장하지 않는다.

plugin은 30초 timeout과 제한된 HTTP 읽기를 쓰고 redirect를 따르지 않는다.
성공은 문자열 JSON이 아닌 `_multimodal=True` dict로 반환하며 `image_url.detail=original`을 보존한다.
native 이미지를 받지 못한 요약 대체, HTTP 오류, timeout, 손상 결과는 판독 성공으로 표현하지 않는다.
한도 오류는 HTTP 422와 `ATTACHMENT_INSPECTION_LIMIT`로 구분해 자동 영역 재조회를 안내한다.
그 밖의 decode 오류는 `ATTACHMENT_INSPECTION_FAILED`다. plugin 오류는 JSON `error` 문자열로 반환해
Hermes의 실패 판정과 Runs API의 `tool.completed.error` 관측에 남는다.
이 경로는 `/v1/runs`이며 별도 session streaming callback의 kwargs 누락 경로를 쓰지 않는다.
실제 설치 gateway의 사건 전달은 배포 뒤 왕복으로 확인한다.
모델에게 작은 글자·가격·품번은 원본 또는 영역을 자동 조회하도록 안내한다.
같은 대화의 첨부 번호와 원본 표시 크기를 매 turn에 복원하며 사용자의 재업로드를 요구하지 않는다.

### 근거와 검증 경계

고정 Hermes v2026.9.24의 registry, tool executor, Responses adapter는 generic envelope의
이미지 목록과 detail을 native `input_image`로 옮긴다. MCP ImageContent는 표시용 글로 바뀌므로 쓰지 않는다.
Hermes core와 private 속성, 전역 monkeypatch, 기존 도구 위장은 사용하지 않는다.
provider가 실제 tool 이미지와 original detail을 수용하는지와 작은 글자의 정확도는 배포 뒤 실제 왕복으로 확인한다.
로컬 검사는 권한, 시각 서명, 원본/crop 픽셀, EXIF 좌표, 한도와 실패 반환을 검증한다.

### 대안

30장 원본 일괄 주입은 요청 본문과 반복 입력 비용을 늘리고 실패 복구도 복잡하게 한다.
사본 경로만 다시 읽는 방법은 작은 글자의 원본 정보를 복구하지 못한다.
짧은 CP grant 발급은 두 번의 HTTP 왕복이 필요하므로 현재 실행 시작 시각에 묶인 hook proof를 먼저 사용한다.
