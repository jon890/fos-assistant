# Phase 01. 로그인 기록과 관리자 활동 응답

**Execution profile**: deep

## 목표

로그인 성공만 기록하고 사용자 발신 메시지의 마지막 시각을 한 번의 집계 질의로 관리자에게 준다.

**범위 외**: 웹 화면과 NextAuth 이벤트 연결, PR, push, 머지와 홈서버 접속.

## 컨텍스트

**근거 문서**: `docs/backend/people.md`의 「관리자에게 보이는 최근 활동」, `docs/backend/schema/users-agents.md`의 「allowed_person」, `docs/frontend/structure.md`의 「관리자 영역」, `docs/flow.md`의 「로그인 활동 기록」.

지금 `PersonList`의 「첫 로그인」은 `Person.joined`라는 app_user 존재 여부이며 시각이 아니다. 사용자 상세 화면은 없어 행별 펼침으로 추가한다. `SignInPolicy.admit`은 판정만 한다. 일반 토큰 필터는 매 요청 `UserProvisioningService.resolve`를 호출하므로 그곳에 로그인 시각을 쓰지 않는다.

## 의도 메모

- 지난 로그인 시각을 created_at으로 추정하지 않는다. 값이 없으면 null이다.
- 메시지 본문은 내보내지 않으며 sender_user_id를 기준으로 USER 메시지만 센다.
- 새 의존은 추가하지 않고 ADMIN 경계와 signin 서명 토큰 검증을 유지한다.

## 작업 항목

### 1. 로그인 성공 기록

`AllowedPerson`에 nullable Instant lastLoginAt과 매핑을 추가한다. `V20261008104501__user_activity.sql`은 allowed_person.last_login_at DATETIME(6) NULL과 chat_message의 (sender_user_id, role, created_at) 집계 인덱스를 추가한다. 기존 데이터는 NULL 유지.
`AllowedPersonRepository`에 enabled=true인 정규화 이메일의 lastLoginAt이 NULL 또는 입력 시각보다 오래될 때만 갱신하는 @Modifying 질의를 추가한다. application의 @Transactional에서 호출한다. 동시 완료가 과거 시각으로 되돌리지 못하게 한다.
`SignInPolicy`에 Clock 기반 완료 기록 메서드를 추가한다. admit은 readOnly 그대로 유지. 꺼졌거나 없거나 빈 주소는 거절한다.
`SignInController`에 POST /api/v1/signin/completed {email}을 추가한다. 기존 requireSignInToken을 먼저 호출하고 완료 기록 성공은 204, 거절은 401 UNAUTHENTICATED. 이 경로도 ControlPlaneJwtFilter 제외 목록과 SecurityConfig의 permitAll 목록에 명시한다. app_user를 만들지 않는다.

### 2. 마지막 대화 집계와 관리자 응답

`UserLastMessage` record는 Long userId, Instant at을 갖는다. ChatMessageRepository는 Collection<Long> userIds를 받아 USER 역할, sender_user_id IN userIds를 group by sender_user_id로 MAX(created_at)하는 JPQL 한 질의를 선언한다.
`UserConversationActivity`는 저장소를 감싼 읽기 application service로 Map<Long,Instant>를 반환한다. 빈 목록은 질의를 생략한다.
`PersonAccessService.list`는 기존 사용자 목록을 한 번 읽어 집계 서비스에 번호 전체를 주고 정규화 이메일로 맞춘다. 이메일이 정규화 뒤 겹치면 가장 최근 메시지를 고른다. 사용자 수에 따라 질의 수가 증가하지 않는다. setEnabled 응답에도 값을 넣는다.
`PersonAccess`와 `PersonView`에 lastConversationAt/lastLoginAt을 연결한다. PersonView는 lastLoginAt, lastConversationAt ISO Instant nullable 칸 두 개를 기존 필드와 함께 반환한다. 생성 응답은 둘 다 null. list/create/update는 ADMIN 경계를 유지한다.

### 3. SignInControllerTest.java, PersonActivityTest.java, UserActivityMigrationTest.java 테스트

수정하는 테스트는 `backend/src/test/java/com/bifos/assistant/people/SignInControllerTest.java`다.
신규 테스트는 `backend/src/test/java/com/bifos/assistant/people/PersonActivityTest.java`와 `backend/src/test/java/com/bifos/assistant/people/UserActivityMigrationTest.java`다.

SignInControllerTest에서 판정은 시각을 바꾸지 않음, 정상 완료 두 번이 새 시각 갱신, 일반 요청으로 시각 유지, 누락/대화용/잘못된 서명/만료 토큰 거절, 꺼짐/없는/빈 이메일 미기록과 미생성, 대소문자 정규화를 확인한다. TestClock으로 시각을 고정한다. 과거 완료 요청도 최근값 유지.
PersonActivityTest에서 여러 사용자·여러 대화의 마지막 USER 메시지, sender와 소유자가 다른 경우, 최신 ASSISTANT/SYSTEM 배제, 메시지 없는 사용자와 아직 미가입, 꺼진 사용자, 이메일 정규화, 목록/변경 JSON null 및 MEMBER list/patch 거절을 확인한다. Hibernate Statistics로 목록 사용자 수가 증가해도 질의는 고정 3회(허용 목록/사용자/집계)인 것을 확인한다.
UserActivityMigrationTest는 Flyway로 모든 마이그레이션을 적용해 칸과 인덱스 및 기존 데이터 NULL을 확인한다. 기존 마이그레이션은 바꾸지 않는다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.people.*' --tests 'com.bifos.assistant.user.RevokedUserTest'
node scripts/check-migration-versions.mjs
```

- `cd backend && ./gradlew test --tests 'com.bifos.assistant.people.*' --tests 'com.bifos.assistant.user.RevokedUserTest'`를 실행해 통과한다. 무거운 검사는 작업 메시지의 heavy-lock 경로로 감싼다.
- `node scripts/check-migration-versions.mjs`가 통과한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/people/domain/AllowedPerson.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/infra/AllowedPersonRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/SignInPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/PersonAccessService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/model/PersonAccess.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/presentation/PeopleDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/presentation/PeopleAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/presentation/SignInController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/UserLastMessage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/UserConversationActivity.java` | 신규 |
| `backend/src/main/resources/db/migration/V20261008104501__user_activity.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/people/SignInControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/PersonActivityTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/people/UserActivityMigrationTest.java` | 신규 |
