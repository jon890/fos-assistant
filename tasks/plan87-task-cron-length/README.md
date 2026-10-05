# plan87 cron 식 길이 검사

`task_trigger.cron_expr` 는 `VARCHAR(100)` 인데 요청의 cron 길이를 검사하지 않아, 100자를 넘으면 저장에서 실패해 500 이 난다.
저장 전에 400 `TASK_SCHEDULE_INVALID` 로 거절한다. 계약은 `docs/backend/task.md` 의 「시각」 표가 갖는다.

| phase | 만드는 것 |
| --- | --- |
| 01 | 서버 길이 검사, 화면 입력 칸 상한, 검사 |

이슈: #177 의 「`TaskDtos` 의 `cron` 길이를 `task_trigger.cron_expr` 의 `VARCHAR(100)` 과 맞춰 검사한다」.
같은 브랜치의 앞 커밋이 #177 의 「`/이름` 으로 시작하는 지시」 항목을 문서로 정했다. 코드는 바꾸지 않는다.
