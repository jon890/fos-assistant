# Phase 01. 관리 등록과 변경의 서비스·컨트롤러·명령을 모은다

**Execution profile**: deep

## 목표

에이전트 관리자의 등록 업무와 HTTP 계약을 분리한다.

**범위 외**: 포맷 설정과 전량 포맷, 다른 관심사의 이동, HTTP wire·DB 스키마·enum 저장 값·업무 시각 변경.

## 컨텍스트

**근거 문서**: `backend/docs/code-architecture.md`, `backend/docs/data-schema.md`, `docs/code-architecture.md`.

착수 기준은 PR #420의 Javadoc 참조 복구가 머지된 실제 main 90a87c27이다.
이 독립 관심사의 현재 원본·consumer 4개는 모두 아래 경로에 있으며 앞 계획의 이동은 아직 main에 없다.
기본 verify_task 검사와 이동 전 agent·shared 회귀는 종료 코드 0이다.
초기 소유 조사는 PR #409가 머지된 48cecd5a 기준이며, 이전 경로·consumer 보완 기준은 실제 origin/main c3bcb21d다.
관찰 저장 PR #411과 금융 claim PR #413의 머지 결과를 이 main에서 읽었다.
최종 확인 main f04753a3는 이 기준과 제품 타입·경로가 같으며 PR #414의 규모 정책 변경을 반영한다.
아래 원본과 consumer는 이 기준의 실제 정의와 대조했다.
착수 시 최신 실제 main으로 다시 대조하고 main 밖의 producer 결과를 완료로 간주하지 않는다.
이 계획의 앞 단계가 옮긴 consumer는 아래 목록과 변경 파일 표에 이미 새 경로로 반영했다.
다른 계획이 먼저 머지됐으면 아래 교차 계획 경로 표의 정확한 목적지로 변경 파일 표를 갱신하고 basic 검사를 다시 통과시킨 뒤 실행한다.
동일 타입을 두 번 이동하거나 producer 브랜치에서 진행하지 않는다.

## 의도 메모

최상위 기능별 의존 순서와 shared 격리를 유지한다.
목적별 묶음은 기능 이름이 먼저 나오고 application/domain/infra/presentation이 그 아래 놓인다.
기존 작은 루트 계약과 primary application은 불필요하게 한 파일 디렉터리로 늘리지 않는다.
설계만 담은 PR과 규칙만 담은 PR은 만들지 않는다.
phase는 검증 가능한 작업 묶음이며 PR 수를 정하지 않는다.
같은 소유 경계의 phase는 관심사, 접근 결합, 동작 위험과 rename을 인식한 실제 diff에 따라 한 PR로 합칠 수 있다.
각 PR에 해당 구현, 회귀, 구조 규칙과 설계 문서를 함께 담고 이동, 포맷, auditing 동작 변경은 관심사별로 분리한다.
200줄을 목표로 다시 나누지 않는다. 운영 변경 상한의 단일 정본은 scripts/pr-size.mjs이며 착수 main의 현재 정책을 적용한다.
규모 예외는 코디네이터의 판단 사항이고 구현자가 상한이나 라벨을 바꾸지 않는다.

## Blocked 조건

전체 Javadoc의 기존 참조 오류 5개 복구가 main에 머지되어야 이 단계의 Javadoc 종료 코드 0을 요구할 수 있다.
복구 작업은 코디네이터가 별도 소유한다.
이 단계에 무관한 오류 수정을 섞거나 doclint 옵션을 끄지 않는다.
착수 시 복구가 아직 머지되지 않았으면 PHASE_BLOCKED로 선행 조건을 보고한다.

지정한 기존 타입이 main에서 사라졌거나 의미·공개 시그니처가 달라졌으면 PHASE_BLOCKED로 코디네이터에게 알린다.

동일 파일의 producer가 아직 쓰고 있으면 실제 main 머지까지 이동하지 않는다.
운영 변경이 scripts/pr-size.mjs의 현재 상한을 넘으면 이 관심사 내부의 의존 묶음 단위로 PR을 나누고 코디네이터에게 결과를 보낸다.
구현자는 상한·suppressions를 늘리거나 규모:예외 라벨을 붙이지 않는다. 규모 예외가 필요하면 실제 diff와 관심사 분할 결과를 코디네이터에게 보고한다.

## 작업 항목

### 1. 정의와 소비자를 함께 옮긴다

| 현재 파일 | 새 파일 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentAdminService.java` | `backend/src/main/java/com/bifos/assistant/agent/admin/application/AgentAdminService.java` |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentCreateCommand.java` | `backend/src/main/java/com/bifos/assistant/agent/admin/application/model/AgentCreateCommand.java` |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentUpdateCommand.java` | `backend/src/main/java/com/bifos/assistant/agent/admin/application/model/AgentUpdateCommand.java` |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | `backend/src/main/java/com/bifos/assistant/agent/admin/presentation/AgentAdminController.java` |


package 선언·import·같은 package의 단순 타입 참조·Javadoc link와 FQN 소비자를 함께 고친다.
Lombok이 만드는 접근과 package-private member도 공개 범위를 유지한다.
SQL 표명과 요청 attribute key, logger의 명시 topic처럼 클래스 위치와 무관한 문자열은 치환하지 않는다.
Spring 컴포넌트 스캔, 엔티티 스캔, ConfigurationPropertiesScan은 AssistantApplication의 루트 아래를 계속 찾는다.

AgentAdminService는 사용자 생성과 달리 수동 profile 등록, ownerEmail 선택과 endpoint probe를 맡아 별도 업무다.
AgentService.requireForAdmin·changeDefaultModel은 기본 모델 업무에서도 쓰므로 이름만 보고 이 서비스로 이동하지 않는다.
AgentEndpointProbe는 실제 HTTP adapter지만 현재 application에 있으며 infra로 옮기는 변경은 이 PR에 섞지 않는다.
호출 가능한 공개 타입이므로 현재 위치를 유지한다.

AgentDtos에서 CreateAgentRequest, UpdateAgentRequest, AdminAgentView를 새 agent.admin.presentation.AgentAdminDtos로 추출한다.
converter는 같은 새 package의 controller와 함께 두므로 공개 범위를 넓히지 않는다.
코드·주인·공개 범위 검증, 그룹 공개 도구 안전 확인, AGENT_BUSY와 connector-managed 거절, HTTP 경로 및 응답 component를 보존한다.
admin 판정은 URL 이름 외에 CurrentUserProvider.requireAdmin 호출로 유지한다.

### 2. 실제 consumer와 테스트를 갱신한다

아래는 import/FQN 및 같은 package의 타입 참조에서 찾은 consumer다.
검사 구현자가 별도 기능을 추정해 넓히지 않고, 이 파일들의 타입 참조와 같은 이름의 fixture를 최신화한다.
변경 파일 표의 목록은 실제 scope이고, 테스트가 실패하면 같은 관심사의 누락된 소비자를 확인한다.

- `backend/src/main/java/com/bifos/assistant/agent/application/AgentAdminService.java`
- `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java`
- `backend/src/test/java/com/bifos/assistant/agent/AgentAdminServiceTest.java`
- `backend/src/test/java/com/bifos/assistant/agent/AgentApiBaseUrlUpdateTest.java`
- `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleFlagsTest.java`
- `backend/src/test/java/com/bifos/assistant/agent/AgentProactiveCheckWritesAdminTest.java`

### 3. 같은 단계의 구조 규칙과 정상·위반 테스트를 넣는다

`backend/src/test/java/com/bifos/assistant/architecture/AgentAdminServicePlacementTest.java`를 @Tag("architecture")로 작성한다.
정상과 위반 소스를 JDK JavaCompiler로 테스트 임시 디렉터리에 컴파일해 ClassFileImporter.importPath로 읽는다.
운영 소스에는 fixture를 넣지 않는다.
규칙은 같은 ArchCondition/ArchRule을 사용하되 fixture의 루트만 인자로 바꾸고, fixture가 select되지 않아 통과하는 오류를 대상 클래스 수 단언으로 막는다.
정상 evaluation은 위반 0, 위반 evaluation은 대상 FQN과 규칙 설명을 포함해야 한다.
공통 JavaCompiler 빌더와 controller→infra, domain→presentation/Web 방향 fixture는 처음 이 공용 검사를 구현하는 PR의 ArchitectureRulesTest에 한 번 둔다.
후속 placement 테스트는 같은 빌더와 production ArchCondition/ArchRule을 재사용하며 이 단계의 소유 타입과 전용 위반 사례만 더한다.
각 단계에서 공통 fixture를 복사하거나 이름만 다른 규칙을 만들지 않는다.
main에 공용 검사가 없다면 현재 관심사의 PR에서 필요한 최소 helper를 한 번 만들고 코디네이터가 공유 파일 소유를 조정한다.
독립 auditing 파일럿이 먼저 진행해도 entity 위치 이동이나 Memory 경계 이동을 선행 조건으로 만들지 않는다. 새 범용 프레임워크나 의존을 만들지 않는다.

이 단계의 배치 규칙은 정확한 타입 역할과 소유 scope로 정의한다.
@Entity·@Embeddable은 entity, 저장 enum은 domain.type, 공개 서비스 값은 application.model, HTTP record는 presentation의 Dtos, Spring @Service는 application, 설정 선언은 config의 판정을 사용한다.
domain 정책 값·infra projection은 application.model로 올리지 않는다.
아직 옮기지 않은 다른 관심사에는 규칙의 전역 검사를 걸지 않는다.
이동한 소유 scope는 raw rule.check로 검사하며 baseline을 만들지 않는다.
기존 순환·층 순서·공용 규칙을 약화시키거나 없애지 않는다.

CI는 .github/workflows/ci.yml의 backend ./gradlew test와 quality scripts/quality.sh check가 이 검사를 실행한다.
실패를 삼키는 옵션이나 continue-on-error를 추가하지 않는다.
해당 rule의 production violation을 임시 checkout에 넣어 같은 Gradle 명령이 0이 아닌 코드로 끝나는 증거를 남긴 뒤 제거한다.
fixture의 실패가 예상대로 잡히는 테스트와 CI 명령 자체의 실패 전파를 구별한다.

### 구조 규칙의 정확한 판정과 fixture 입력

| 규칙 | 판정 조건 | 정상 fixture | 위반 fixture |
| --- | --- | --- | --- |
| `AGENT_ADMIN_APPLICATION_PLACEMENT` | 수동 등록/변경 서비스 AgentAdminService와 명령 두 개는 agent.admin.application 및 model에만 있다. Entity·Repository는 admin에 두지 않는다. admin은 공유 lifecycle과 domain을 호출하나 domain/infra는 admin을 import하지 않는다. | agent.admin.application의 AgentAdminService가 agent.application.AgentLifecycleService 사용 | admin.application에 @Entity를 두거나 agent.domain이 AgentAdminService 사용 |
| `AGENT_ADMIN_HTTP_CONTRACT` | /api/v1/admin/agents의 등록·목록·수정 컨트롤러는 agent.admin.presentation에 있으며, 세 record는 그 AgentAdminDtos에만 둔다. mapped method에서 CurrentUserProvider.requireAdmin을 호출해야 한다. URL 형식만으로 권한이 증명되지는 않아 기존 HTTP 거절 회귀도 실행한다. | 정상 package의 Controller가 requireAdmin 후 service 호출 | 해당 controller를 일반 presentation으로 되돌리거나 requireAdmin 호출을 제거 |

확정한 역할별 목적지: AgentAdminService는 com.bifos.assistant.agent.admin.application; AgentCreateCommand는 com.bifos.assistant.agent.admin.application.model; AgentUpdateCommand는 com.bifos.assistant.agent.admin.application.model; AgentAdminController는 com.bifos.assistant.agent.admin.presentation.

각 규칙은 raw .check로 CI에 연결한다.
@Tag("architecture")인 fixture 테스트는 archTest와 test에서 모두 선택되어야 한다.
영구 baseline과 전체 suppressions를 만들지 않는다.
첫 기능에서 추가한 역할 조건은 이후 단계에서 같은 구현을 확장하며 이름만 같은 별도 검사 함수를 만들지 않는다.

### 4. 구현된 경계와 근거를 같은 PR의 정본에 옮긴다

backend/docs/code-architecture.md에는 이 이동표의 새 소유 기능과 층 방향 및 검사 이름을 적고, backend/AGENTS.md의 관련 기존 배치 설명을 새 위치에 맞춘다.
도메인 값·DTO·enum·설정·admin의 의미는 이 단계가 실제 구현한 범위만 적는다.
tasks 번호나 임시 보고서 경로를 정본이 가리키게 하지 않는다.
이번 dispatch의 설계 제안은 코디네이터가 가진 임시 보고서에 있고 정본 편집은 이 구현 PR에서만 한다.
완료한 phase의 오래 남을 결정은 먼저 정본에 옮기고 완료 계획은 해당 구현 PR에서 지운다.

## 검증

제품 구현의 무거운 Gradle·MySQL 검사는 코디네이터가 지정한 두 슬롯과 공용 heavy-lock의 운영 조건을 따른다.
CI 전체 검증의 사용자 승인도 코디네이터가 조정한다.
계획 기본 검사는 이 조건의 대상이 아니며 작성자가 실제 실행한다.
아래 명령은 모두 저장소 루트 기준이다.
Javadoc 참조 오류, 컴파일 실패, 대상 회귀 실패, ArchUnit 위반, Checkstyle error, Spotless 실패가 하나라도 있으면 완료하지 않는다.

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.agent.*' --tests 'com.bifos.assistant.shared.*' --tests '*AgentAdminServicePlacementTest' --console=plain
cd backend && ./gradlew qualityCheck --console=plain
cd backend && ./gradlew javadoc --console=plain
git diff --check
node scripts/check-file-length.mjs
```

정상·위반 fixture와 실제 대상 기능의 기존 정상/거절/잠금/실패 회귀가 모두 통과해야 한다.
package-private 접근 확대 0, 원래 FQN의 살아 있는 타입 참조 0, 추가 runtime dependency 0, 이동 전후 공개 record component·enum 문자열·설정 키 차이 0을 확인한다.
push 전 scripts/check-local.sh --skip-browser를 실행한다.
원격 CI 판정은 remote-verification.md에 둔다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/AGENTS.md` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/admin/application/AgentAdminService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/admin/application/model/AgentCreateCommand.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/admin/application/model/AgentUpdateCommand.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/admin/presentation/AgentAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/admin/presentation/AgentAdminDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentAdminService.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentCreateCommand.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentUpdateCommand.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentAdminServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentApiBaseUrlUpdateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleFlagsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentProactiveCheckWritesAdminTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/AgentAdminServicePlacementTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRulesTest.java` | 수정 |
