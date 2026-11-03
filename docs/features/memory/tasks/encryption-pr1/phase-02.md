# phase 2: 기억의 저장과 읽기

설계: [`design.md`](../../design.md) 의 REQ1, REQ4, REQ6, REQ7.

## 할 일

- `Memory`, `MemoryRevision` 에 저장 필드와 `data_key_id`, 처음 꺼낼 때 푸는 `title()`, `content()`, `readable()` 을 둔다
- `MemoryTexts`(application)와 `MemoryTextListener`(infra, `POST_LOAD` 와 `PRE_INSERT`)를 더한다
- 새 기억을 넣는 자리를 `MemoryTexts.insert` 로, 고치기와 revision 을 `MemoryTexts` 암호화로 바꾼다
- 검색 후보와 임베딩 원천 질의를 엔티티 읽기로 바꾼다
- `sealed()` 판정을 `readable()` 로 바꾸고, 목록 DTO 에 `unreadable` 을 더하고, 화면이 고치기를 막는다
- 관리자 화면 테스트로 REQ7 을 고정한다

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.memory.*' --tests 'com.bifos.assistant.context.*' --tests 'com.bifos.assistant.mcp.*Memory*'
```
