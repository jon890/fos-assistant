# plan84 결과물 정리의 동시 쓰기 보존과 지운 표시 대조

GitHub 이슈 #168, #169 를 고친다. 두 phase 는 순서대로 돈다. phase 2 가 phase 1 의 `ArtifactStore` 를 고친다.

| phase | 무엇 |
| --- | --- |
| 1 | `ArtifactStore.deleteOlderThan` 을 대화별 잠금 안에서 다시 판정하고 지우기 직전 재확인한다 |
| 2 | `ArtifactCleaner` 가 지운 뒤 행 쪽에서 지운 표시를 맞추고, `markDeleted` 가 기간 시작 전 행에만 적는다 |

계약은 `docs/backend/artifact.md` 의 「보관 기간이 지난 파일을 지울 때」 와 「지운 표시를 다시 맞추기」 가 갖는다.
