# 인수 검증 계획: prompt-frame

| 요구사항 | 확인 |
| --- | --- |
| REQ24 | `ArtifactTest` 가 실행 입력에서 인물 수, 사람별 줄, 한국어 지시문 줄, 고칠 때의 예외 줄을 본다. `test_image_source.py` 가 인물 줄이 든 지시문이 fos-ctx 를 지나 그대로 이미지 도구로 가는지 본다 |
| REQ25 | `ArtifactTest` 가 이어지는 장면 줄과 그림 제목 뒤의 참고 링크를 본다. `ImageSourceEndpointTest` 가 저장된 꼴의 링크를 다시 받는지, `test_image_source.py` 가 앞 그림과 링크를 함께 참고로 받는지 본다 |
| REQ26 | `ArtifactTest` 가 비율과 구도 줄을 본다. `test_image_source.py` 가 `aspect_ratio` 가 그대로 가는지, 계약 테스트가 Hermes 의 비율 값 셋과 기본값을 본다 |

운영의 품질 비교는 배포 뒤 코디네이터가 한다(스펙 DECISION42).
