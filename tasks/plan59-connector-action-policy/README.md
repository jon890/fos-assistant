# plan59 커넥터 동작 정책과 승인

PR 둘로 나눠 올린다.

| PR | phase | 내용 |
| --- | --- | --- |
| 1 | 01~06, 11 | manifest `schema: 2`, 이름 대응, hook 의 정책 질의, 판정과 기록. 승인이 필요한 호출은 통과시키고 기록만 남긴다. 11 은 위임 결과의 외부 데이터 표시다 |
| 2 | 07~10 | 승인 엔진. 기록만 하던 호출을 승인 대기로 바꾼다. 이 PR 에서 계획서를 지운다 |

**실행 순서는 01~06, 11, 07~10 이다.** phase 11 은 PR 1 에 들어가므로 06 뒤에 돌린다.

결정은 `docs/adr/ADR-049-*.md`, `docs/adr/ADR-050-*.md`, 계약은 `docs/connectors.md` 의 「도구 정책」 과 「승인」 에 있다.

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 루트 `AGENTS.md` 의 「공개 저장소」 를 지킨다. 주소에 포트 숫자를 붙여 적지 않는다
- `backend/src/main` 과 `web/src` 에 특정 서비스의 이름을 적지 않는다(`test/unit/connector-neutral.test.ts`)
- backend 의 새 코드는 `backend/AGENTS.md` 의 규칙을 지키고 기준 파일에 기대지 않는다. `Instant.now(clock)`, `shared.util.Sha256`, `@Enumerated` 의 enum 은 `domain.type`, 테스트에 한국어 `@DisplayName`
- 기능 변경과 포맷을 한 커밋에 섞지 않는다. 포맷이 필요하면 `scripts/quality.sh fix` 가 바꾼 것을 따로 커밋한다
- Flyway 번호는 이 계획이 V45, V46, V47 을 쓴다. `origin/main` 을 합칠 때 번호가 겹치면 main 의 최신 다음 번호로 옮긴다
- 주석과 Javadoc 은 한국어로 쓴다
