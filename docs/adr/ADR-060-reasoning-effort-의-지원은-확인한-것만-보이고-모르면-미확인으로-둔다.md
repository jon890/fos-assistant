## ADR-060: reasoning effort 의 지원은 Hermes 가 알린 것만 확인으로 보이고 모르면 미확인으로 둔다

- **status**: `accepted`.
- **결정**: 모델마다 reasoning 지원과 reasoning 끄기(`none`) 지원을 각각 `SUPPORTED`, `UNSUPPORTED`, `UNKNOWN` 셋으로 나눠 API 와 화면에 싣는다.
  Hermes 의 `/api/model/options` 가 값을 주지 않은 칸은 `UNKNOWN` 이다. 우리가 참이나 거짓으로 채우지 않는다.
  **미지정(effort 를 보내지 않음)과 `none`(reasoning 을 끔)은 다른 의도로 취급한다.**
  `none` 은 그 모델의 끄기 지원이 `SUPPORTED` 일 때만 선택하고 저장한다. `minimal` 은 지원을 확인할 신호가 없어 선택지에 넣지 않는다.
  provider 이름별로 effort 를 바꾸는 규칙은 두지 않는다. 받은 값을 어떻게 전달하고 줄이고 빼는지는 Hermes 가 정한다.
- **맥락**: effort 선택지는 고정 목록(`low` 부터 `max`)이었고, Hermes 가 지원 여부를 밝히지 않은 모델은 backend 가 참으로 채웠다.
  화면은 확인된 지원과 모르는 것을 구분하지 못했다. `none` 과 `minimal` 은 요청 검증에서 거절돼 reasoning 을 끄는 길도 없었다.
  Hermes v2026.9.24 소스를 읽어 확인한 것은 [Runs API 문서](../hermes/runs-api.md)의 「reasoning effort 는 `model_options` 로 그 실행에만 준다」에 있다.
  `none` 은 요청 단위로 reasoning 을 끄고, 모르는 값은 버려 profile 설정으로 돈다.
  `/api/model/options` 의 `capabilities` 는 모델별 `reasoning` 과, aggregator provider 에 한해 `can_disable_reasoning` 을 준다.
  상류는 `supported_efforts` 를 일부러 내보내지 않는다. 실제 지원보다 적게 알려 주기 때문이다.
  `reasoning` 은 상류 카탈로그가 모르면 상류가 참으로 채운다. 그래서 참은 「지원이 확인됨」 이 아니라 「지원하지 않는다는 신호가 없음」 에 가깝다.
- **대안 기각**:
  - **고정 enum 에 `none`, `minimal` 을 더해 모든 모델에 보인다.** provider 마다 받는 값이 달라 축소와 생략이 사용자가 모르는 사이에 일어난다. 요청값이 적용값처럼 읽힌다.
  - **provider 이름별 지원 표를 우리가 갖는다.** Hermes 의 변환 규칙을 복제하게 되고 Hermes 를 올릴 때마다 어긋난다. ADR-001 이 막은 방향이다.
  - **미확인 모델에서 effort 선택을 막는다.** 고를 수 있는 것을 못 고르게 된다. 지금 동작을 유지하고 미확인임을 알린다.
  - **`minimal` 도 `none` 처럼 넣는다.** 지원을 확인할 신호가 어디에도 없다. 신호가 생기면 같은 구조로 칸을 더한다.
- **결과**: 요청값과 적용값은 여전히 다르다. 실행 줄의 effort 는 우리가 보낸 값이고 effort 출처 구분은 그대로다.
  - 얻는 것: 지원이 확인된 것과 모르는 것이 API 와 화면에서 갈라진다. reasoning 을 끄려는 의도가 미지정과 섞이지 않는다.
  - 감당할 것: aggregator 가 아닌 provider 의 모델은 `none` 지원이 `UNKNOWN` 이라 끄기를 고르지 못한다. `reasoningCapable` 참거짓 표를 쓰던 화면과 계약이 바뀐다.
- **적용 범위**: 대화의 모델 선택과 에이전트 기본 모델이다. 그룹의 모델 단계 정의는 모델을 가리키는 profile 이 하나가 아니라 `none` 을 받지 않는다.
