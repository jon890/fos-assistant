/**
 * 모델이 기록한 관찰이 실제로 받은 입력보다 많이 읽었다고 주장하는지 판정하는 테스트 계약이다.
 *
 * <p>Control Plane 은 provider 의 vision 지원을 판정하지 않는다(ADR-20261009 / native-image-input).
 * Hermes 가 보조 vision 모델의 설명 글로 바꾸거나 이미지를 뗀 채 다시 시도해도 Control Plane 응답에는 드러나지 않는다.
 * 그래서 실제 흐름의 경로는 `UNKNOWN` 이고, 대역 시나리오만 경로를 정해 넣는다.
 */

/** provider 가 사진을 어떤 모양으로 받았는지다. */
export type VisionRoute =
  /** 모델이 이미지 파트를 그대로 받았다 */
  | "NATIVE"
  /** 모델이 vision 을 지원하지 않아 보조 모델의 설명 글만 받았다 */
  | "AUX_DESCRIPTION"
  /** 상류가 이미지를 거절해 이미지를 뗀 사본으로 다시 시도했다 */
  | "STRIPPED"
  /** Control Plane 이 실행 입력에 싣지 못했다 */
  | "NOT_EMBEDDED"
  /** 경로를 관측하지 못했다. 실제 provider 흐름의 기본값이다 */
  | "UNKNOWN";

export type ObservationClaim = {
  kind: "VISUAL" | "OCR" | "USER";
  confidence: "CONFIRMED" | "UNCERTAIN";
};
export type RecordedObservation = {
  assetKey: string;
  status: "SUCCEEDED" | "PARTIAL" | "NEEDS_REVIEW" | "FAILED";
  coverage: "ORIGINAL" | "OVERVIEW" | "CROP" | "FIRST_FRAME" | null;
  claims: readonly ObservationClaim[];
};
/** 사진의 성질. 합성 생성기의 측정에서 정한다. */
export type AssetTraits = {
  assetKey: string;
  bytesSha256: string;
  expectedMarker: number;
  /** NONE 은 글씨 없음, LABEL 은 사본에서도 읽히는 글씨, ORIGINAL_ONLY 는 원본에서만 읽히는 작은 글씨 */
  text: "NONE" | "LABEL" | "ORIGINAL_ONLY";
};

export type ClaimVerdict = {
  rows: {
    assetKey: string;
    route: VisionRoute;
    verdict: "OBSERVED" | "FALLBACK" | "NOT_RECEIVED" | "UNMEASURED";
    /** 픽셀 표식으로 자리를 구분할 수 있는지. 같은 바이트는 첨부 번호로만, 같은 표식은 바이트로만 구분한다 */
    pixelIdentity: "UNIQUE" | "SHA_ONLY" | "AMBIGUOUS";
  }[];
  delivery:
    | "NATIVE"
    | "FALLBACK"
    | "PARTIAL_FAILURE"
    | "FAILED"
    | "UNMEASURED";
};

const RECEIVED: readonly VisionRoute[] = ["NATIVE", "AUX_DESCRIPTION"];
const confirmed = (observation: RecordedObservation, kind: ObservationClaim["kind"]) =>
  observation.claims.some((claim) => claim.kind === kind && claim.confidence === "CONFIRMED");

/**
 * 받은 입력보다 많이 읽었다는 주장을 오류로 거절하고, 사진마다 받은 경로와 run 전체의 전달 상태를 돌려준다.
 * 경로가 `UNKNOWN` 인 사진은 모델이 무엇을 주장하든 `UNMEASURED` 로 남는다.
 */
export function judgeObservationClaims(
  traits: readonly AssetTraits[],
  routes: ReadonlyMap<string, VisionRoute>,
  observations: readonly RecordedObservation[],
): ClaimVerdict {
  const byKey = new Map(traits.map((trait) => [trait.assetKey, trait]));
  if (byKey.size !== traits.length) throw new Error("DUPLICATE_ASSET_KEY");
  const recorded = new Map<string, RecordedObservation>();
  for (const observation of observations) {
    if (!byKey.has(observation.assetKey) || recorded.has(observation.assetKey))
      throw new Error("OBSERVATION_ASSET_INVALID");
    recorded.set(observation.assetKey, observation);
  }
  const rows = traits.map((trait) => {
    const route = routes.get(trait.assetKey);
    if (!route) throw new Error("ROUTE_MISSING");
    const observation = recorded.get(trait.assetKey);
    if (observation) {
      if (!RECEIVED.includes(route) && route !== "UNKNOWN" && observation.status !== "FAILED")
        throw new Error("OVERCLAIM_NOT_RECEIVED");
      if (
        route === "AUX_DESCRIPTION" &&
        (observation.status === "SUCCEEDED" ||
          observation.coverage === "ORIGINAL" ||
          observation.coverage === "CROP" ||
          confirmed(observation, "VISUAL") ||
          confirmed(observation, "OCR"))
      )
        throw new Error("OVERCLAIM_FALLBACK");
      if (trait.text === "NONE" && confirmed(observation, "OCR"))
        throw new Error("OVERCLAIM_TEXT");
      if (
        trait.text === "ORIGINAL_ONLY" &&
        confirmed(observation, "OCR") &&
        observation.coverage !== "ORIGINAL" &&
        observation.coverage !== "CROP"
      )
        throw new Error("OVERCLAIM_SMALL_TEXT");
    }
    const sameBytes = traits.filter((other) => other.bytesSha256 === trait.bytesSha256).length;
    const sameMarker = traits.filter((other) => other.expectedMarker === trait.expectedMarker).length;
    return {
      assetKey: trait.assetKey,
      route,
      verdict:
        route === "UNKNOWN"
          ? ("UNMEASURED" as const)
          : route === "NATIVE"
            ? ("OBSERVED" as const)
            : route === "AUX_DESCRIPTION"
              ? ("FALLBACK" as const)
              : ("NOT_RECEIVED" as const),
      pixelIdentity:
        sameBytes > 1
          ? ("AMBIGUOUS" as const)
          : sameMarker > 1
            ? ("SHA_ONLY" as const)
            : ("UNIQUE" as const),
    };
  });
  const count = (verdict: ClaimVerdict["rows"][number]["verdict"]) =>
    rows.filter((row) => row.verdict === verdict).length;
  const delivery: ClaimVerdict["delivery"] =
    count("UNMEASURED") > 0
      ? "UNMEASURED"
      : count("NOT_RECEIVED") === rows.length
        ? "FAILED"
        : count("NOT_RECEIVED") > 0
          ? "PARTIAL_FAILURE"
          : count("FALLBACK") > 0
            ? "FALLBACK"
            : "NATIVE";
  return { rows, delivery };
}

/**
 * 대역 provider 의 경로를 정한다. Control Plane 의 `embedded` 사건에서 싣지 못한 사진은 늘 `NOT_EMBEDDED` 다.
 * 실은 사진은 provider 모양에 따라 바뀐다. `vision` 은 그대로, `unsupported` 는 보조 설명 글로, `rejecting` 은 이미지를 뗀다.
 */
export function fakeProviderRoutes(
  embeddedKeys: readonly string[],
  missingKeys: readonly string[],
  provider: "vision" | "unsupported" | "rejecting",
): Map<string, VisionRoute> {
  const routes = new Map<string, VisionRoute>();
  const route: VisionRoute =
    provider === "vision" ? "NATIVE" : provider === "unsupported" ? "AUX_DESCRIPTION" : "STRIPPED";
  for (const key of embeddedKeys) routes.set(key, route);
  for (const key of missingKeys) {
    if (routes.has(key)) throw new Error("ROUTE_CONFLICT");
    routes.set(key, "NOT_EMBEDDED");
  }
  return routes;
}
