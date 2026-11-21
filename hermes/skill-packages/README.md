# 스킬 묶음 사본

에이전트에 「스킬 묶음 올리기」 로 넣는 스킬의 원본이다. 폴더 하나가 스킬 하나다.
플러그인에 딸리지 않아 붙이기로 설치되지 않는다. 관리하는 사람이 대상 에이전트에 올린다.
받기와 검사 규칙은 [에이전트와 스킬](../../docs/features/agent-skill.md) 의 「스킬 묶음 받기와 검사」 와 「옮겨 온 스킬 묶음」 이 갖는다.

## 지키는 것

- **공개 저장소다.** 사용자의 건강 정보, 경력, 외주 조건 같은 개인 값을 본문에 넣지 않는다. 스킬은 그 값을 기억(`memory_search`, `memory_read`)에서 읽고, 새 사실은 `memory_remember` 로 남긴다.
- 폴더 이름과 `SKILL.md` 앞머리 `name` 이 같다. 새 스킬의 설명은 60자까지다.
- 파일은 `SKILL.md` 와 `references/`, `templates/`, `scripts/`, `assets/` 아래에만 둔다. `scripts/` 가 있는 스킬은 셸 실행 공간이 있는 에이전트에만 올라간다.
- 개인 값으로 만든 파일을 셸 작업 폴더에 남기는 스킬(`freelance-opportunity-reporter`)은 주인 한 사람만 쓰는 비공개 에이전트에만 올린다. 그룹에 공개한 에이전트는 쓰는 사람들이 같은 셸 작업 공간을 함께 쓴다.
- 예약 실행을 전제로 쓰지 않는다. 사용자가 부를 때 도는 절차로 쓴다.

## 올리기

폴더 하나를 그 이름의 맨 위 디렉터리째 zip 으로 묶어 에이전트 설정의 스킬 묶음 올리기에 넣는다. 맨 위 디렉터리 하나는 검사가 벗긴다.

```bash
# cwd: hermes/skill-packages
zip -r "$TMPDIR/<스킬>.zip" "<스킬>" -x '*/__pycache__/*'
```

## 스킬

| 스킬 | 하는 일 |
| --- | --- |
| `daily-health-coaching` | 그날의 식단, 운동, 중단 기준을 엮은 건강 코칭 |
| `health-progress-intake` | 재활 경과 보고를 날짜별 기억으로 남김 |
| `weekly-clinic-summary` | 최근 경과로 주간 요약과 진료 질문 목록 |
| `personalized-healthy-meal-research` | 건강 조건에 맞는 반복 가능한 식단 조사 |
| `freelance-opportunity-reporter` | 외주 공고 수집과 지원 판단 HTML 리포트. `scripts/` 를 쓴다 |
| `freelance-proposal-preparer` | 공고와 RFP 로 견적, 일정, 지원문, HTML 제안서 |

`freelance-opportunity-reporter/scripts/test_tools.py` 는 스크립트의 단위 테스트다. 그 폴더에서 `python3 -B test_tools.py` 로 돌린다.
