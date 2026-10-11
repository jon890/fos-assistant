## ADR-20261012 / bodyless-request-diagnostics: 관리 bootstrap이 요청 진단 writer를 본문 없는 writer로 교체한다

- **status**: `accepted`
- Date: 2026-10-12

### 맥락

고정 Hermes의 preflight와 두 terminal 오류 경로는 요청 전체를 dump한다.
본문과 이미지, URL·인증 값·오류 원문을 새 요청 진단과 로그에 남기지 않고 오류 종류·크기·파트 수만 기록해야 한다.
provider 입력과 비용·기존 오류 결과, 실패 실행 기록은 보존한다.

[ADR-001](../../../docs/adr/ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md)의 검토 순서를 따랐다.
profile의 `HERMES_DUMP_REQUESTS`는 preflight만 제어한다. Runs API에서 사본을 지워도 생성 금지는 해결되지 않는다.
plugin hook과 PluginManager는 설치 실패를 모든 관리 interpreter의 기동 실패로 전달하는 공통 계약이 없다.
상류에 disable 또는 명시 opt-in을 제안하는 방향을 유지하되, 이번에는 관리 entrypoint에서만 내부 module 속성 교체를 허용한다.
[ADR-088](ADR-088-대시보드-plugin-은-감싸는-경로의-바꿔-끼우기를-두고-기대는-hermes-내부-지점을-계약-시험으로-확인한다.md)은 검증 방식의 선례이며 이 새 예외의 승인을 대신하지 않는다.

### 결정

`hermes/runtime/`에 일반 addon을 둔다. 단일 공개 설치 API는 `install_bodyless_writer(*, resolver, fingerprints)`다.
관리 bootstrap이 첫 writer 호출 전에 설치하고 process-global 소유 module이 resolver·지문·writer identity를 유지한다.
동일 resolver 객체와 지문만 멱등이며 불일치는 고정 `InstallationError`다. profile plugin cleanup에 원문 복원 함수를 주지 않는다.
Hermes core와 venv 파일, `sys.executable`과 subprocess 전역은 고치지 않는다.

| 책임 | 소유 |
| --- | --- |
| 고정 enum·숫자만 생성하는 typed writer, bounded 집계, POSIX dirfd 쓰기 | 이 저장소 |
| helper signature, lazy forward와 세 callsite의 내부 의존 선언·source 실행 검사 | `hermes/tests/hermes_contract.py`와 계약 시험 |
| 신뢰된 agent 바인딩 resolver, 독립적으로 승인·봉인한 release manifest | `fos-home-infra` |
| early bootstrap, 좁은 cron adapter, 모든 관리 interpreter와 정상 lifecycle 연결 | `fos-home-infra` |

resolver는 요청 인자·cwd·전역 profile을 보지 않고 검증한 agent 바인딩의 절대 디렉터리 또는 `None`만 돌려준다.
writer는 모든 부모를 `O_NOFOLLOW` dirfd로 열고 leaf를 `O_EXCL`, `O_NOFOLLOW`, 0600으로 만든다.
입력 복제·변경·전체 직렬화, 임의 객체의 property·str·repr 접근, 원문 fallback과 stdout 옵션은 사용하지 않는다.
추출·파일 쓰기 실패는 원문과 예외를 출력하지 않고 `None`으로 끝나며 upstream 오류 처리와 session 저장은 계속된다.
지원 형식과 불완전 집계의 의미는 [본문 없는 요청 진단](../hermes-contract.md#본문-없는-요청-진단)이 갖는다.

내부 교체는 공식 upstream API가 아니다. 고정 source에서 helper code·signature와 다섯 파일 지문을 검사한다.
관리 owner의 agent 초기화·constructor·close·delegate와 명시 secret/home 문맥,
cron parent·child 진입점도 같은 고정 source의 별도 일반 계약으로 선언한다.
constructor의 호출 시점 import와 명시 부모 identity가 있어야 정상 lifecycle에서 바인딩을 전달하고 해제할 수 있다.
profile 설정과 Runs API는 이 identity 수명을 제어하지 못하고 plugin hook은 필수 early 설치를 보장하지 못한다.
따라서 ADR-001의 예외 범위를 이 관리 경계까지 확장하되 core 파일과 프로세스 전역은 변경하지 않는다.
cron module의 executable 조회만 제한하는 private proxy도 공식 Hermes API가 아니다.
추가 선언은 공개 설치 API와 다섯 writer 파일 지문을 바꾸지 않는다.
관리 manifest는 source와 writer bundle·adapter·interpreter·image를 독립적으로 봉인해야 한다.
기동 중 관측한 자기 hash나 profile이 바꿀 수 있는 기대값은 신뢰 기준으로 쓰지 않는다.
설치 실패 때 bootstrap은 credential과 요청을 읽거나 main을 시작하지 않고 고정 오류로 끝낸다.
이미 만든 부모 execution은 failed로 남길 수 있으며 범용 reservation·new run·provider 횟수 0을 새 요구로 두지 않는다.

### 검증과 한계

합성 fixture는 Chat Completions, Responses, Anthropic, Gemini와 native tool 이미지 모양을 검사한다.
오류·reason canary, 입력 불변, 임의 객체·cycle·깊이·파트·검사량 제한, 0600·symlink·충돌·동시 profile·I/O 실패를 확인한다.
공식 source의 helper code와 lazy forward, 세 호출부를 분리 실행해 입력과 실패 분류·session 저장 결과를 대조한다.
함수 분리 실행은 전체 Hermes import나 실제 provider 실행을 대신하지 않는다.
추가 관리 의존은 정확한 signature·함수 AST·context-local 저장소·cron module 진입점의 source 검사다.
initializer의 전역 alias 전환, 부모 인자 삭제, 전역 home fallback, 프로세스 전역 interpreter 교체와
ack 게시 제거의 반례를 거절한다. 전체 agent와 실제 parent·provider 네트워크는 이 검사에서 실행하지 않는다.

현재 source와 설치 plugin의 알려진 alias 범위에 한정한다. 기존 직접 callable alias와 설치 뒤 helper 변조는 우회 반례다.
재설치는 identity 불일치를 검출하지만 실행 중 악성 코드와 임의 host 전체를 보호하는 보안 경계가 아니다.
실제 parent·s6·PM relaunch·gateway 정상 restart와 두 profile reload/unload는 private 후속 수락 조건이다.
SDK의 malformed stdout 처리와 framework의 기존 오류 로그 가림도 별도 수락 조건이다.
이 writer가 새 진단을 가리는 것만으로 SDK·framework·session·trajectory 전체가 본문 없이 남는다고 판정하지 않는다.
운영 배포와 실제 Hermes 왕복 전까지 운영 보호 완료로 보고하지 않는다.
private prototype의 합성 검증은 별도 근거이며 공개 actual main 반영과 private 활성화 수락은 아직 남아 있다.
