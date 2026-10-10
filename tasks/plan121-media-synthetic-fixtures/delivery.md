# 구현 전달과 PR 분할

각 phase는 컨텍스트에 적힌 선행 producer가 main에 머지된 뒤 최신 main 시작/일반 merge, 남은 index의 기본 verify_task.py 종료 0, 해당 phase 구현 순서로 진행한다.
이 계획의 첫 phase는 신규 producer 의존이 없다. 설치된 planning 번들 SKILL_DIR를 사용해 저장소 root에서 기본 검사를 실행한다.

```bash
# cwd: 저장소 root
python3 "$SKILL_DIR/scripts/verify_task.py" plan121-media-synthetic-fixtures
```

producer 미머지나 기본 검사 실패는 PHASE_BLOCKED다. --audit/--staged 대체, 빈 파일 선생성, 검사기 무력화는 금지한다.
각 phase PR 머지 뒤 완료 phase를 삭제한 남은 index로 다음 기본 검사를 다시 실행한다.

새로 시작하는 lane은 선행 머지를 확인하고 최신 main을 fetch한 뒤 그 main에서 브랜치를 만든다.
기존 작업 브랜치는 최신 main을 fetch하고 일반 merge로 합친다. rebase와 force push로 이력을 다시 쓰지 않는다.
합친 head에서 해당 phase의 로컬 검사와 CI를 다시 확인하고 일반 push로 전달한다.

선행 조건: A/B와 병렬이다. C 소유의 fixture/helper/test만 수정하고 fake Hermes 서버와 운영 코드는 건드리지 않는다.

이 plan은 한 관심사 PR로 전달하며 내부 phase는 순서대로 검증한다.
PR마다 운영 변경줄은 scripts/pr-size.mjs 기준으로 1,000 이하이어야 한다.
구현 없이 문서만 PR로 올리지 않는다.
phase 1만 전달할 때는 해당 구현과 해당 설계 절만 담는다.
완료한 phase 파일은 그 구현 PR에서 지우고 index.json은 남은 phase만 실행하도록 갱신한다.
마지막 phase의 구현 PR에서 남은 plan 디렉터리를 지운다.
다른 미구현 plan 디렉터리는 어느 구현 PR에도 섞지 않는다.
코디네이터가 설계 파일의 해당 절과 ADR을 골라 통합하고 구현 워커는 다른 단계 설계 내용을 일괄 stage하지 않는다.
독립 A/B/C는 다른 lane의 파일과 shared docs를 수정하지 않는다.

A/B/C 구현은 격리된 워크트리에서 진행한다. 같은 작업 공간을 쓴다면 backend·web 빌드 산출물을 공유하는 quality/check-local/MySQL 검증은 순차 실행한다.

JPEG/PNG/GIF만 기존 JDK로 실제 바이트·표식 픽셀을 검증한다. WebP metadata와 실제 판독 UNMEASURED를 구분하고 실제 첫 프레임 양성은 머지된 PR #394 runtime 회귀와 후속 통합 검증으로 전달한다. 별도 CI job의 Pillow 공유와 새 의존성은 가정하지 않는다. reducer 통과는 실제 provider/브라우저 성공이 아니다.
