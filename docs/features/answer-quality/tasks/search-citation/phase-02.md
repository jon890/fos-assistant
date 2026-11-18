# Phase 02. 답변 품질 평가 세트를 만든다

**Execution profile**: standard

## 목표

스펙의 스무 문항을 운영 Control Plane 과 Hermes, 모델을 그대로 거쳐 돌리고 숫자를 내는 평가를 `test/model-eval/answer/` 에 둔다. PR 마다 고치기 전과 뒤를 같은 문항으로 비교하려는 것이다.

**범위 외**: 평가를 운영에서 실제로 돌리는 일(코디네이터가 배포 전후에 한다), 여러 turn 문항과 기억 문항(뒤 PR), 모델에게 채점시키기.

## 컨텍스트

- 틀은 블로그 사진 평가다. `test/model-eval/blog/run.ts`(명령 `send`, `score`), `score.ts`(순수 함수), `api.ts`(Control Plane 클라이언트), 단위 테스트 `blog-photo-eval-score.test.ts`, `blog-photo-eval-data.test.ts` 를 먼저 읽고 같은 모양을 따른다. 결정은 `docs/adr/ADR-20261020-blog-photo-eval.md` 다
- `api.ts` 의 타입은 판정에 쓰는 칸만 갖는다. 답 본문, 사건 종류, 걸린 시간은 API 응답에 이미 있다: `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java`(메시지의 `content`), `usage/tree/application/dto/ExecutionNode.java`(`latencyMs`), `usage/tree/application/dto/ExecutionEventView.java`(`eventType`, `toolName`, `failed`)
- 단위 테스트는 CI 의 `node --test 'test/**/*.test.ts' 'scripts/**/*.test.ts'` 가 돈다. `scripts/ci/ci-paths.mjs` 는 `test/model-eval/blog` 의 `*.test.ts` 만 「언제나 도는 unit 만」 으로 둔다
- 토큰과 기본 주소는 표준 출력과 결과 파일에 쓰지 않는다. 대화 공개 식별자와 실행 번호만 쓴다(`blog/run.ts` 머리 주석). 저장소는 공개라 접속 값과 그것을 만드는 방법을 어디에도 적지 않는다
- 문항은 `docs/features/answer-quality/spec.md` 의 「평가 세트」 표 그대로다. ★ 여덟 문항이 사람 비교 대상이다

**요구사항**: REQ7

**근거 문서**: `docs/features/answer-quality/spec.md`(REQ7, 「평가 세트」, DECISION8, DECISION10), `docs/features/answer-quality/design.md`(「평가의 입력과 출력」, DECISION5, DECISION6)

## 의도 메모

- 「검색했는가」 는 실행 사건이 아니라 답의 「출처」 링크로 센다. OpenAI 내장 검색은 검색 사건을 남기지 않아 두 검색 서비스를 같은 잣대로 보려는 것이다. 사건 수는 참고 칸으로만 낸다
- 통과 기준은 스펙 DECISION10 이다. 출처 기준의 분모는 통과 방식이 「출처」 인 열일곱 문항이고 열다섯 이상이 통과다. 「오늘 날짜」 문항은 날짜 일치로 따로 본다
- 링크가 403 이나 429 로 막힌 것은 틀린 링크가 아니다. 「확인 못 함」 으로 따로 세고 열림 비율의 분모에서 뺀다
- 문항 하나가 메시지 여럿을 가질 수 있게 둔다. 뒤 PR 이 여러 turn 문항을 더한다

## 작업 항목

### 1. Control Plane 클라이언트를 옮기고 넓힌다

- `test/model-eval/blog/api.ts` 를 `test/model-eval/control-plane.ts` 로 옮긴다(`git mv`). `blog/run.ts` 의 import 만 바꾼다
- 타입에 메시지의 답 본문(`content`), 실행 노드의 `latencyMs`, 사건의 `eventType` 을 더한다. 블로그 평가의 동작은 바뀌지 않아야 한다
- 모델 칸 목록 `TIERS` 는 `blog/run.ts` 의 내보내지 않는 상수다. `blog/run.ts` 를 import 하면 그 `main()` 이 돌므로 `TIERS` 를 `control-plane.ts` 로 옮겨 두 평가가 함께 쓴다

### 2. `test/model-eval/answer/questions.json`

문항마다 `id`, `category`(쇼핑·가격, 여행·장소, 뉴스·시세, 생활 지식), `messages`(보낼 글의 차례), `check`(`sources`, `date`, `none` 중 하나), `compare`(사람 비교 대상인지)를 둔다. 스무 문항, `sources` 열일곱, `date` 하나, `none` 둘, `compare` 여덟이다.

### 3. `test/model-eval/answer/score.ts`

네트워크를 쓰지 않는 순수 함수다.

- 답에서 「출처」 절을 찾고 그 안의 마크다운 링크와 맨 주소를 뽑는다. 절의 머리는 `출처` 로 시작하는 제목 줄이나 굵은 글 줄(`## 출처`, `**출처**`, `출처:`)을 받는다
- 문항 통과: `sources` 는 「출처」 절에 링크가 하나 이상, `date` 는 답에 평가를 돌린 날의 한국 날짜(월과 일)가 들었는지, `none` 은 늘 통과
- 사건 수: 실행 트리 전체에서 `eventType` 이 `TOOL_COMPLETED` 인 사건만 세어 `web_search`, `web_extract` 의 수와 실패 수를 낸다
- 세트 통과: `sources` 문항 중 통과 수가 열다섯 이상, 확인한 링크 중 열린 비율이 90% 이상, `date` 문항 통과. 사람 비교(여덟 중 여섯)는 사람이 적은 값을 받아 함께 판정하고, 비어 있으면 「사람 비교 대기」 로 낸다
- 보고서 글(Markdown 표): 문항마다 범주, 통과, 「출처」 링크 수, 열린 수, 확인 못 한 수, 걸린 시간, 검색과 본문 읽기 수와 실패 수. 끝에 세트 판정과 ★ 문항마다 「비슷하다/모자라다」 와 까닭을 적을 빈 칸

### 4. `test/model-eval/answer/run.ts`

- `send --out <결과 디렉터리> [--question <id>]... [--tier <칸>] [--label <이름표>]`: 문항마다 새 대화를 만들어 메시지를 차례로 보내고 끝날 때까지 기다린다. 결과 파일에 문항 id, 대화 공개 식별자, 실행 번호, 이름표, 칸, 시작 시각을 적는다
- `score --out <결과 디렉터리> [--compare <사람 비교 파일>] [--keep]`: 답과 실행 트리를 읽고, 「출처」 링크마다 요청을 보내 2xx·3xx 는 열림, 403·429 는 확인 못 함, 그 밖과 시간 초과는 닫힘으로 센다. 요청마다 시간 제한을 두고 같은 주소는 한 번만 확인한다. 보고서를 결과 디렉터리에 쓴다
- 접속 값은 블로그 평가와 같은 환경 변수 `FOS_EVAL_BASE_URL`, `FOS_EVAL_TOKEN`, `FOS_EVAL_AGENT_CODE` 로 받는다(`FOS_EVAL_BLOG_CATEGORY` 는 쓰지 않는다). 칸은 `--tier` 가 없으면 `FOS_EVAL_TIER` 를 읽고 그것도 없으면 대화의 기본 칸으로 돈다. 오류 글과 보고서에 토큰과 주소를 넣지 않는다
- `--help` 는 환경 변수와 `--out` 을 검사하기 전에 사용법을 내고 0 으로 끝난다(블로그 평가에는 이 옵션이 없다)
- `--compare` 파일은 `{"version": 1, "results": [{"id": "<문항 id>", "verdict": "similar" 또는 "lacking", "note": "<한 줄 까닭>"}]}` 다. 보고서 끝의 빈 칸은 사람이 읽고 이 파일을 쓰게 하는 안내이고 입력은 이 파일이다
- 종료 코드: 세트 통과 0, 기준 미달 1, 사용법 오류와 보내기·읽기 오류인 문항이 있으면 2. 자동 기준은 넘었는데 사람 비교가 비어 있으면 「사람 비교 대기」 를 내고 0 으로 끝난다
- 날짜 문항의 「평가를 돌린 날」 은 결과 파일에 적은 그 문항의 시작 시각을 한국 시간으로 바꾼 날이다
- 통과한 대화를 지우고 실패한 대화를 남기는 동작과 `--keep` 은 블로그 평가와 같게 한다. ★ 문항의 대화는 사람이 답을 읽어야 하므로 지우지 않는다

### 5. 단위 테스트와 CI 경로

- `test/model-eval/answer/answer-eval-score.test.ts`: 「출처」 절이 있는 답과 없는 답, 절 밖의 링크만 있는 답(통과 아님), 날짜가 든 답과 틀린 날짜, 시작·끝 사건이 함께 있을 때 수가 두 배가 되지 않는지, 403 이 분모에서 빠지는지, 열다섯과 열넷의 경계, 사람 비교가 비었을 때
- `test/model-eval/answer/answer-eval-data.test.ts`: `questions.json` 의 형식, id 중복 없음, `check` 값과 `compare` 수
- `scripts/ci/ci-paths.mjs` 의 `test\/(?:support|model-eval\/blog)` 규칙을 `test/model-eval/` 아래의 `*.test.ts` 가 폴더 깊이와 상관없이 맞도록 넓히고 `scripts/ci/ci-paths.test.ts` 에 사례를 더한다

## 검증

```bash
node --test test/model-eval/answer/answer-eval-score.test.ts test/model-eval/answer/answer-eval-data.test.ts test/model-eval/blog/blog-photo-eval-score.test.ts test/model-eval/blog/blog-photo-eval-data.test.ts scripts/ci/ci-paths.test.ts
cd test && npm run typecheck
node test/model-eval/answer/run.ts --help
```

기대값: 테스트와 타입 검사 통과. 마지막 줄은 환경 변수 없이 사용법을 내고 0 으로 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/model-eval/control-plane.ts` | 신규 (`blog/api.ts` 에서 옮김, 타입 넓힘) |
| `test/model-eval/blog/api.ts` | 삭제 |
| `test/model-eval/blog/run.ts` | 수정 (import 경로) |
| `test/model-eval/answer/questions.json` | 신규 |
| `test/model-eval/answer/score.ts` | 신규 |
| `test/model-eval/answer/run.ts` | 신규 |
| `test/model-eval/answer/answer-eval-score.test.ts` | 신규 |
| `test/model-eval/answer/answer-eval-data.test.ts` | 신규 |
| `scripts/ci/ci-paths.mjs` | 수정 (model-eval 단위 테스트 규칙) |
| `scripts/ci/ci-paths.test.ts` | 수정 |
