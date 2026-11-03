# phase 3: 이관과 옛 보정 걷기

설계: [`design.md`](../../design.md) 의 REQ3, REQ8 과 「이관 순서와 실패 처리」.

## 할 일

- `MemoryTextMigration` 을 더하고 `MemoryContentBackfill`, `MemoryContentSealer` 와 그 테스트를 지운다. `MemoryContentCipher` 는 풀기만 남긴다
- 실제 MySQL 테스트(`@Tag("mysql")`)로 이관 전후 건수와 줄마다의 평문 해시, 옛 key 줄 0건, 데이터 key 를 풀지 못할 때의 거절을 본다
- `docs/features/memory.md`, `backend/docs/data-schema.md` 를 새 동작에 맞춘다

## 검증

```bash
# cwd: 저장소 root
scripts/dev/check-local.sh --fast
```

MySQL 테스트는 Draft CI 의 `backend-mysql` 결과로 판정한다.
