# PR2 계획: fos-ctx 와 계약 테스트를 v0.21.6, v0.23.2 두 버전에서 맞춘다

스펙 [`spec.md`](../../spec.md) 의 REQ5, REQ6, REQ7, REQ8, REQ12. 경계는 [`design.md`](../../design.md) 의 「fos-ctx」, 「계약 테스트」 절이 갖는다.

| 단계 | 할 일 | 확인 |
| --- | --- | --- |
| 1 | `hermes_contract.py` 에 계열 표지와 `HERMES_VARIANTS`, `expected()` 를 두고 계약 테스트들이 그 값을 읽게 한다. 본문 없는 요청 진단 묶음은 지원 계열 밖이면 건너뛴다 | `check-hermes-contract.sh <v0.21.6 소스>`, `check-hermes-contract.sh <v0.23.2 소스>` 가 0 |
| 2 | 승인 관문 계약(`APPROVAL_GUARD`, `APPROVAL_WRITE_POINTS`)과 `test_approval_guard_points` 를 더한다 | 1 과 같다 |
| 3 | fos-ctx `approval_guard.py` 로 위험 명령, 승인 경로 쓰기, `computer_use` 를 막는다 | `run-hermes-python-tests.py test_fos_ctx.py` |
| 4 | fos-ctx `tool_guard` 가 session 은 있고 `tool_call_id` 가 없는 호출에 `code-exec:` 번호를 만든다 | 3 과 같다 |
| 5 | 계약 문서, ADR-20261008, fos-ctx README 를 고친다 | `check-local.sh --fast` |
| 6 | 이 계획서를 지운다 | |
