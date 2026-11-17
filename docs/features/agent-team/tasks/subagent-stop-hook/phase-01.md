# Phase 01. fos-ctx 의 끝 소식과 등록 본문의 새 칸

**Execution profile**: standard

## 목표

fos-ctx 가 `subagent_stop` 에서 끝 소식을 PR6 의 경로로 보내고, `subagent_start` 의 등록 본문에 `reports_stop: true` 와 `child_goal` 을 넣는다. 그러면 PR6 이 배포된 운영에서 내장 위임의 결과로 다음 답이 열린다.

**범위 외**: Control Plane 과 화면(PR6 이 끝냈다). 운영 왕복(코디네이터, 스펙 「배포 뒤 왕복 확인」).

## 컨텍스트

**요구사항**: REQ10

**근거 문서**: `docs/features/agent-team/design.md` 의 「PR7 fos-ctx 끝 소식」, `hermes/plugins/fos-ctx/README.md` 의 「하위 에이전트 session 등록 계약」, 「하위 에이전트 끝 소식 계약」, `hermes/docs/hermes-contract.md` 의 「하위 에이전트의 끝 hook 은 결과 줄보다 먼저 온다」

- 등록 코드는 `hermes/plugins/fos-ctx/subagent.py` 의 `build_registration`, `_post`, `subagent_start` 다. 서명은 `context.py` 의 `sign_subagent` 다.
- `__init__.py` 가 plugin 의 이름을 다시 내보내고 `register` 가 hook 을 단다. `plugin.yaml` 의 `hooks` 와 같아야 한다.
- 서버의 본문 검사는 `backend/.../mcp/application/dto/SubagentStop.java` 다. 고치지 않는다.
- Hermes 소스 계약 선언은 `hermes/tests/hermes_contract.py`, 검사는 `hermes/tests/test_hermes_contract.py` 의 `HermesSourceTest` 다.

## 작업 항목

1. `context.py` 에 `STOP_VERSION = "v1-subagent-stop"` 과 `sign_subagent_stop` 을 더한다.
2. `subagent.py`
   - `stop_url()`: `FOS_CTX_SUBAGENT_URL` 의 끝 `/` 를 떼고 `/stop` 을 붙인다. 없으면 빈 글.
   - `build_registration` 이 `reports_stop: True`, `child_goal`(글이 아니거나 비면 None)을 넣는다.
   - `build_stop(parent_session_id, child_session_id, child_status, child_summary)` 가 본문과 토큰을 돌려준다.
   - 두 hook 이 쓰는 보내기 반복(2회, 5xx 와 연결 실패만 다시)을 한 함수로 묶는다. 등록 로그 글은 바꾸지 않는다.
   - `subagent_stop(**kwargs)` 는 어떤 예외도 내지 않는다.
3. `__init__.py`, `plugin.yaml`: hook 을 더하고 버전을 올린다. 설명과 머리 설명을 고친다.
4. `hermes_contract.py` 에 `SUBAGENT_HOOKS` 를 선언하고 `test_hermes_contract.py` 에 소스 검사를 더한다.
5. `test_fos_ctx.py` 에 끝 소식 테스트를 더한다.
6. 문서: fos-ctx README 의 「자식 session 을 등록한다」 와 확인 절차, `hermes-contract.md` 의 끝 hook 절에 v0.23.2 확인을 더한다. 기능 파일 「Hermes 내장 위임 결과」 의 마지막 문장을 지금 동작으로 고친다.

## 검증

```bash
# cwd: 저장소 root
python3 -I -m unittest discover -s hermes/tests -p 'test_fos_ctx.py'
python3 -I -m unittest discover -s hermes/tests -p 'test_hermes_contract.py'
HERMES_SOURCE=<v0.21.6 소스> HERMES_CONTRACT_REQUIRED=1 python3 -I -m unittest discover -s hermes/tests -p 'test_hermes_contract.py'
HERMES_SOURCE=<v0.23.2 소스> HERMES_CONTRACT_REQUIRED=1 python3 -I -m unittest discover -s hermes/tests -p 'test_hermes_contract.py'
scripts/dev/check-local.sh --fast
```
