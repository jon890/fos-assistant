# 가계부 연결

사용자는 `/connections/accountbook`에서 가계부 개인 연동 토큰을 등록한다.
연결은 사용자별 전용 profile과 비공개 에이전트를 갖는다.
임의 plugin을 설치하는 화면은 제공하지 않는다.

## API와 상태

| 경로 | 요청 | 결과 |
| --- | --- | --- |
| `GET /api/v1/connections/accountbook` | 없음 | 자신의 연결 상태 |
| `POST /api/v1/connections/accountbook/families` | `token` | 토큰 주인의 가족 목록 `uuid`, `name` |
| `POST /api/v1/connections/accountbook` | `token`, 선택 `familyUuid` | 등록 또는 토큰 교체 |
| `POST /api/v1/connections/accountbook/check` | 없음 | 설치 상태와 MCP probe 재확인 |
| `DELETE /api/v1/connections/accountbook` | 없음 | 토큰 제거와 에이전트 비활성화 |
| `GET /api/v1/admin/connections/accountbook` | 없음 | 같은 그룹의 연결 목록, ADMIN 전용 |
| `POST /api/v1/admin/connections/accountbook/{userId}/confirm` | 없음 | 운영 반영 완료 확인, ADMIN 전용 |

상태 응답은 `status`, `tokenPrefix`, `familyUuid`, `checkedAt`, `agentCode`, `restartRequired`를 갖는다.
관리자 목록은 `userId`, `displayName`, `status`, `agentCode`, `restartRequired`만 반환한다.
다른 사용자의 토큰 앞부분과 가족 UUID는 목록에 넣지 않는다.
등록 전에는 `DISCONNECTED`이며 선택 값은 null이다.
`PENDING`은 토큰을 등록했으나 실행 준비가 끝나지 않은 상태다.
`READY`는 설치가 켜져 있고 재시작이 필요 없으며 MCP probe에서 가계부 도구를 확인한 상태다.
probe는 공유 gateway의 실제 실행 확인을 대신하지 않는다.
운영에서 실제 도구를 호출하는 확인은 `fos-home-infra`가 맡는다.

등록 요청은 profile, 사용자 번호, plugin 경로, MCP 정의를 받지 않는다.
화면은 토큰으로 가족 목록을 불러오며 가족이 하나면 자동으로 고른다.
여럿이면 사용자가 선택한 가족 UUID를 등록 요청에 함께 보낸다.
가족과 함께 쓰는 사용자들은 각자 발급한 토큰으로 같은 가족을 선택한다.
가족 조회는 연결이나 토큰을 저장하지 않으며 조회한 토큰은 응답하지 않는다.
토큰을 바꾸면 조회한 가족 목록을 비우고 다시 확인한다.
서버가 로그인 사용자와 저장된 에이전트 바인딩으로 profile을 정한다.
`ACCOUNTBOOK_API_BASE_URL`은 Control Plane 환경 변수로 받으며 HTTPS 공인 경로여야 한다.
등록 전에 그 주소의 `/families`를 한 번 불러 토큰과 선택 가족의 권한을 확인한다.
redirect를 따라가지 않는다.
인증 실패, 가족 권한 없음, 외부 호출 실패는 원문 응답을 노출하지 않는 고정 오류다.
오류 코드는 `ACCOUNTBOOK_TOKEN_REJECTED`(400), `ACCOUNTBOOK_FAMILY_FORBIDDEN`(403),
`ACCOUNTBOOK_UNAVAILABLE`(503), 외부 설치·확인·해제 실패인 `CONNECTOR_OPERATION_FAILED`(502)다.

## 설치와 실패 처리

기존 `AgentLifecycleService`로 안전한 profile과 비공개 에이전트를 만든다.
연결용 에이전트는 사용자가 지울 수 없으므로 사용자당 에이전트 상한을 거치지 않고 상한 계산에서도 빠진다.
연결 확인 전에는 에이전트를 끈다.
연결용 에이전트의 공개 범위, 주인, 도구, 성격과 스킬은 일반 편집 경로로 바꾸지 못한다.
연결 화면에서 토큰 등록과 확인, 해제만 한다.

Control Plane은 `PUT /api/connectors`에 `profile`, `plugin: fos-accountbook`, `enabled`만 보낸다.
인프라 plugin이 신뢰된 manifest를 읽어 MCP 서버, persona와 선택 스킬 경로를 설치한다.
허용 도구는 `accountbook`과 Control Plane MCP `fos-assistant`이며 셸, 파일, `skills` 도구는 닫는다.
신뢰된 가계부 스킬 본문은 persona에 넣는다.

**허용 목록은 Control Plane이 줄인다.**
새 profile의 설정 틀은 `platform_toolsets.api_server`에 `delegation`과 `fos-assistant`를 넣는다.
인프라 plugin의 설치는 그 목록에 `accountbook`을 더할 뿐 내장 도구를 빼지 않는다.
그대로 두면 `delegation`이 켜진 채 남아, 내장 도구 미노출을 요구하는 확인이 READY로 넘어가지 못한다.
실제로 그렇게 모든 연결이 `PENDING`에 머물렀다.

| 시점 | 쓰는 목록 |
| --- | --- |
| 등록할 때, 설치 요청 전 | `["fos-assistant"]`. 설치가 `accountbook`을 더한다 |
| 연결 확인과 관리자 반영 완료에서 켜진 내장 도구가 보일 때 | `["fos-assistant", "accountbook"]` |

목록은 `PUT /api/config`의 `platform_toolsets.api_server`로 쓴다.
확인 경로는 설치의 enabled와 configured가 참일 때만 쓰고, 쓴 뒤 다시 읽어 내장 도구가 비었는지 판정한다.
이미 연결된 사용자도 토큰을 다시 넣지 않고 연결 확인만으로 READY가 된다.

토큰은 `PUT /api/env`로 `ACCOUNTBOOK_API_TOKEN`에 쓴다.
공통 주소와 선택 가족도 `ACCOUNTBOOK_API_BASE_URL`, `ACCOUNTBOOK_FAMILY_UUID`에 쓴다.
`ACCOUNTBOOK_PRIVATE_DIR`는 인프라 plugin이 profile별로 정한다.
env 전달 근거는 [MCP profile 비밀값 계약](hermes/mcp-profile-credentials.md)에 있다.

같은 사용자의 등록, 확인과 해제는 사용자 행 잠금으로 순서대로 처리한다.
토큰 교체와 해제 전에 에이전트를 끄고 상태를 `PENDING`으로 둔다.
`desired_enabled`는 이번 등록의 env와 설치 단계가 모두 성공해 활성화 후보가 되었는지를 뜻한다.
등록과 교체를 시작할 때 false로 바꾸고 모든 외부 반영이 성공한 뒤에만 true로 둔다.
해제 시작 때도 false로 둔다.
false인 연결은 확인이나 관리자 반영 완료로 READY가 되지 않는다.
외부 API가 실패해도 이전 토큰으로 실행할 수 있는 활성 상태로 되돌리지 않는다.
등록 실패 뒤에는 다시 등록하거나 해제할 수 있어야 한다.
새 profile 생성 실패는 기존 생성기의 정리 절차를 따른다.
외부 호출 실패는 별도 예외로 반환하면서 비활성화와 `PENDING`을 커밋한다.
DB 커밋 자체가 실패하면 이미 반영한 env나 plugin 변경은 되돌리지 못한다.
운영에서 남은 변경을 확인하고 다시 등록하거나 해제해 상태를 맞춘다.

해제는 `DELETE /api/env`로 토큰을 제거하고 plugin을 끈다.
선택 가족을 비운 등록과 해제는 `ACCOUNTBOOK_FAMILY_UUID` 환경 항목도 제거한다.
에이전트와 연결 행은 이력을 위해 남긴다.
기존 MCP 프로세스의 환경 값은 파일 변경만으로 바뀌지 않으므로,
설치 응답의 `restart_required`가 참이면 관리자 반영 대기로 보인다.
공유 gateway 재시작을 사용자 요청에서 실행하지 않는다.
가계부 토큰 폐기는 사용자가 가계부 설정에서 한다.
가계부 설정에서 토큰을 폐기하면 다음 요청부터 거절되므로 즉시 외부 접근을 막을 수 있다.
최초 설치는 새 profile의 자동 MCP 발견을 사용한다.
이미 설치된 연결의 토큰 교체, 환경 항목 삭제와 해제는 재시작 필요 상태를 반환한다.
해제 뒤에도 `restartRequired`를 표시해 기존 프로세스 정리가 필요하다는 것을 알린다.

`GET /api/connectors?profile=`은 `profile`과 `connectors` 배열을 반환한다.
알려진 plugin 항목은 `plugin`, `enabled`, `configured`를 갖고 미설치이면 두 판정은 모두 false다.
GET에는 재시작 판정이 없으므로 연결 확인만으로 저장된 `restartRequired`를 지우지 않는다.
저장된 대기 값과 각 env·설치 변경 응답의 `restart_required`를 논리 OR로 저장한다.
도중 호출이 실패해도 앞선 응답의 true를 보존한다.
관리자는 실제 gateway 반영을 마친 뒤 연결 화면에서 반영 완료를 확인한다.
대기 연결은 설치의 enabled와 configured, MCP probe, 내장 도구 미노출을 다시 검사해 READY로 바꾼다.
해제 연결은 설치의 disabled를 확인하고 DISCONNECTED를 유지한 채 재시작 대기를 지운다.
일반 연결 확인도 활성화 후보가 아닌 연결의 disabled를 확인해 DISCONNECTED로 바꿀 수 있지만 재시작 대기는 보존한다.
이 확인도 대상 사용자 행을 잠그며 ADMIN과 같은 그룹인지 검사한다.

## 저장과 비밀값

`accountbook_connection`은 사용자와 에이전트 바인딩, 상태, 토큰 앞 8자,
선택 가족 UUID, 마지막 확인 시각과 재시작 필요 여부만 저장한다.
활성화 후보 여부도 저장해 실패한 등록이나 해제가 기존 토큰의 실행을 다시 허용하지 못하게 한다.
토큰 원문과 해시는 저장하지 않는다.
브라우저는 연결 등록을 제출한 직후 토큰 입력을 비우고 다시 표시하지 않는다.
가족을 고르는 동안은 작성 중인 토큰 입력을 사용하며 가족 조회가 실패해도 입력을 비운다.
요청 record의 문자열 표현, 외부 API 오류, 로그와 응답에 원문을 남기지 않는다.
인프라의 대시보드 권한과 운영 확인은 `fos-home-infra`가 소유한다.
