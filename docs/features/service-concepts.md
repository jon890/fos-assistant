# 서비스 개념

사용자가 대화에서 서비스의 기능(예약 작업, 기억, 할 일, 외부 서비스 연결, 모델 단계, 에이전트 고르기, 파일 공간)을 평소 말로 부르면 에이전트가 알아듣고 직접 처리하거나 그 화면으로 안내하는 기능이다.

covers: `backend/src/main/java/com/bifos/assistant/context/application/ServiceConceptsInstructions.java`, `backend/src/main/java/com/bifos/assistant/mcp/application/McpConnectorListTool.java`, `test/model-eval/concepts/`, `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactService.java`

요구와 결정은 스펙 [`service-concepts/spec.md`](service-concepts/spec.md), 구조는 설계 [`service-concepts/design.md`](service-concepts/design.md) 가 갖는다.
스펙의 PR1 이 들어오면 모든 실행의 공통 지시문에 개념 목록이 들고, 에이전트는 직접 못 하는 기능을 그 화면 링크로 안내한다. 예약 작업, 할 일과 기억, 연결 붙이기의 직접 처리는 뒤 PR 이 더한다.

## 개념 목록

글의 원본은 `ServiceConceptsInstructions` 다. 모든 범위의 공통 지시문에서 답변 형식과 도구 호출 지침 바로 뒤에 든다([`answer-quality.md`](answer-quality.md) 의 「지침」). 대화 turn 입력의 파일 공간 단락(`ArtifactService.FILE_SPACE_RULES`)은 그대로 둔다.

## 연결 안내

`connector_list` 가 요청자의 플러그인마다 연결 상태, 이 에이전트에 붙은 상태, 연결 화면 링크를 준다. 결과 칸과 링크 규칙은 [설계](service-concepts/design.md) 의 「`connector_list`」 가 갖는다.

## 평가 세트

에이전트가 개념을 알아듣는지 운영 Control Plane, Hermes, 실제 모델을 거쳐 돌려 본다. 틀은 [답변 품질 평가](answer-quality.md) 와 같고 CI 와 배포에서는 판정의 단위 테스트만 돈다.
지침이나 도구 설명을 고치는 PR 은 머지 전에 바뀌기 전 운영으로 기준선을, 배포 뒤 다시 돌린 결과를 남긴다(스펙 DECISION33).

| 파일 | 갖는 것 |
| --- | --- |
| `test/model-eval/concepts/questions.json` | 문항마다 개념, 보낼 글, 기대(링크, 도구, 있어야 할 낱말, 없어야 할 낱말) |
| `test/model-eval/concepts/run.ts` | `send` 는 문항마다 새 대화를 만들어 보내고, `score` 는 답과 실행 트리로 판정해 보고서를 쓴다. 사용법은 `--help` |
| `test/model-eval/concepts/score.ts` | 판정 규칙. 네트워크를 쓰지 않는 순수 함수이고 같은 폴더의 단위 테스트가 검사한다 |

운영에 따라 달라지는 값(다른 에이전트의 code와 이름, 플러그인 id와 이름)은 문항에 심지 않고 실행할 때 환경 변수로 준다. 값이 없으면 그 문항은 건너뛴다.
평가 에이전트는 평가 계정이 주인인 비공개 에이전트여야 연결 링크의 `?agent=` 문항이 맞는다. 세트는 돌린 문항의 85% 이상(올림)이 통과하면 통과다.

개념 목록의 화면 주소와 도구 이름은 `test/repo/service-concepts.test.ts` 가 실제 화면 경로와 도구 목록에 있는지 본다.
