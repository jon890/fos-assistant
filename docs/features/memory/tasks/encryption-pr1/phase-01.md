# phase 1: 그룹 데이터 key

설계: [`design.md`](../../design.md) 의 REQ2.

## 할 일

- 마이그레이션 하나(DDL)로 `user_data_key.group_id`, `user_id` NULL 허용, CHECK, 유일 제약, `memory`·`memory_revision` 의 `data_key_id` 와 넓힌 `title` 을 더한다. 컬럼마다 한국어 `COMMENT` 를 단다
- `crypto.domain.KeyOwner`, `OwnedTextCipher` 를 더하고 `DataKey`, `UserDataKey`, `DataKeyWriter`, `DataKeyService`, `DataKeyRewrapper` 를 주인 기준으로 바꾼다
- 기존 `TextCipher`, `BinaryCipher` 의 메서드는 바꾸지 않는다

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*DataKeyServiceTest' --tests '*ChatMessageEncryptionTest' --tests '*UserDataKeyPlacementTest'
```
