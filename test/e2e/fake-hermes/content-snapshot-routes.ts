/**
 * 대시보드 plugin 의 private snapshot 경로 `POST /api/connectors/{id}/snapshot` 대역이다.
 *
 * <p>요청과 응답의 모양, 상태 code 는 `hermes/plugins/dashboard-profile-api/connector_snapshot.py` 와
 * `connector_snapshot_job.py` 를 따른다. 실제 worker 와 브라우저는 띄우지 않는다. start 는 jobId 마다 상태를 한 번만 만들고,
 * status 는 그 stamp 의 job 만 읽는다. 일반 실행 경로의 `{saved:true}` 를 저장 완료로 쓰지 않는다.
 */
import { createHash } from "node:crypto";
import { type IncomingMessage, type ServerResponse } from "node:http";
import { type FakeHermesState } from "./state.ts";
import { catalogOf } from "./connectors.ts";
import { FAKE_DASHBOARD_TOKEN } from "./runtime-fixtures.ts";
import { readBody, send } from "./lifecycle.ts";

export const CONNECTOR_SNAPSHOT_PATH = /^\/api\/connectors\/([a-z0-9-]+)\/snapshot$/;

const STAMP_KEYS = ["protocol", "snapshotId", "jobId", "snapshotHash", "bindingId", "connectionId",
  "bindingUpdatedAt", "connectionUpdatedAt", "profileHash"];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HASH = /^[0-9a-f]{64}$/;
const DECIMAL = /^[1-9][0-9]{0,18}$/;
const MICROS = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/;

type Json = Record<string, unknown>;

const sha256 = (value: string) => createHash("sha256").update(value, "utf8").digest("hex");
const hash = (value: unknown) => sha256(JSON.stringify(value));
const sameKeys = (value: unknown, keys: readonly string[]): value is Json =>
  typeof value === "object" && value !== null && !Array.isArray(value)
  && Object.keys(value).length === keys.length && keys.every((key) => key in value);

/**
 * `hermes/connectors/naver-blog/src/content-compiler.ts` 의 `compileContentDraft` 와 같은 결과를 만든다.
 *
 * <p>그 파일은 zod 를 쓰고 E2E 는 의존성 설치 없이 돌므로 import 하지 않고 tuple 과 해시 계산만 옮긴다. 입력 검사는 Control Plane
 * 이 먼저 했고, Control Plane 이 응답 전체를 다시 계산해 대조하므로 어긋나면 preview 가 실패한다.
 */
function compile(draft: Json, assets: Json[]): Json {
  const byId = new Map(assets.map((asset) => [asset.assetId as string, asset]));
  const lines: string[] = [];
  const canonicalBlocks: unknown[] = [];
  const assetManifest: Json[] = [];
  const photoNotes: Record<string, string> = {};
  const noteTuples: [number, string][] = [];
  (draft.blocks as Json[]).forEach((block, position) => {
    switch (block.type) {
      case "paragraph":
        lines.push(block.text as string);
        canonicalBlocks.push(["paragraph", block.text]);
        break;
      case "image": {
        const asset = byId.get(block.assetId as string);
        if (asset === undefined) throw new Error("invalid_input");
        const number = assetManifest.length + 1;
        lines.push(`[사진 ${number}: ${asset.fileName as string}]`);
        if (block.caption !== undefined) lines.push(block.caption as string);
        const ref = block.observationRef as Json | undefined;
        canonicalBlocks.push(["image", block.assetId, block.caption ?? null, ref ? [ref.id, ref.revision] : null]);
        assetManifest.push({ position, assetId: asset.assetId, sourceFingerprint: asset.sourceFingerprint, fileName: asset.fileName });
        if (asset.observationNote !== undefined) {
          photoNotes[String(number)] = asset.observationNote as string;
          noteTuples.push([number, asset.observationNote as string]);
        }
        break;
      }
      case "sticker":
        lines.push(`[스티커: ${block.code as string}]`);
        canonicalBlocks.push(["sticker", block.code]);
        break;
      case "map":
        lines.push(`[지도: ${block.name as string} | ${block.address as string}]`);
        canonicalBlocks.push(["map", block.name, block.address]);
        break;
      default:
        throw new Error("invalid_input");
    }
  });
  const payload: Json = {
    title: draft.title, category: draft.category, tags: draft.tags, body: lines.join("\n"),
    ...(assetManifest.length > 0 ? { photo_dir: assets[0]!.photoDir } : {}),
  };
  const payloadFingerprint = hash([payload.title, payload.category, payload.tags, payload.body, payload.photo_dir ?? null]);
  const assetTuples = assetManifest.map((asset) => [asset.position, asset.assetId, asset.sourceFingerprint, asset.fileName]);
  const snapshotHash = hash([1, "content-compiler-v1", draft.draftId, draft.revision, canonicalBlocks, assetTuples,
    payloadFingerprint, noteTuples]);
  return {
    schemaVersion: 1, compilerVersion: "content-compiler-v1", draftId: draft.draftId, revision: draft.revision,
    canonicalBlocks, payload, photoNotes, assetManifest, payloadFingerprint, snapshotHash,
  };
}

/** `_job_args` 와 같은 검사다. 맞지 않으면 예외를 던진다. */
function checkJobArgs(profile: unknown, operation: string, args: unknown): Json {
  const keys = operation === "status" ? ["stamp"] : ["snapshot", "jobId", "ticket", "stamp"];
  if (!sameKeys(args, keys) || !sameKeys(args.stamp, STAMP_KEYS)) throw new Error("invalid");
  const stamp = args.stamp;
  const ok = stamp.protocol === "content-snapshot-v1"
    && [stamp.snapshotId, stamp.jobId].every((value) => typeof value === "string" && UUID.test(value))
    && [stamp.snapshotHash, stamp.profileHash].every((value) => typeof value === "string" && HASH.test(value))
    && [stamp.bindingId, stamp.connectionId].every((value) => typeof value === "string" && DECIMAL.test(value))
    && [stamp.bindingUpdatedAt, stamp.connectionUpdatedAt].every((value) => typeof value === "string" && MICROS.test(value))
    && typeof profile === "string" && stamp.profileHash === sha256(profile);
  if (!ok) throw new Error("invalid");
  if (operation === "start") {
    const snapshot = args.snapshot as Json | null;
    if (args.jobId !== stamp.jobId || typeof args.ticket !== "string" || !/^[\x21-\x7e]{1,4096}$/.test(args.ticket)
        || typeof snapshot !== "object" || snapshot === null || snapshot.snapshotHash !== stamp.snapshotHash) {
      throw new Error("invalid");
    }
  }
  return stamp;
}

/** status 결과 한 벌이다. 허용 밖의 저장 수는 null 이고 종료 시각은 밀리초 세 자리다. */
function statusResult(stamp: Json, status: "running" | "succeeded" | "failed" | "unknown", errorCode: string | null): Json {
  const finished = status === "running" ? null : new Date().toISOString();
  return {
    job_id: stamp.jobId, stamp, status,
    stage: status === "running" ? "fill" : "state",
    save_clicked: status === "succeeded" || status === "unknown",
    finished_at: finished,
    saved_before: status === "succeeded" ? 3 : null,
    saved_after: status === "succeeded" ? 4 : null,
    error_code: status === "failed" ? errorCode ?? "editor_failed" : status === "unknown" ? "save_unconfirmed" : null,
  };
}

/** private snapshot 경로를 처리한다. 다른 경로면 false 다. */
export function createContentSnapshotRoutes(state: FakeHermesState) {
  const snap = state.contentSnapshot;
  const reject = (response: ServerResponse, status: number, error: string) => {
    const body = { ok: false, error };
    snap.errorBodies.push(JSON.stringify(body));
    send(response, status, body);
  };

  return async (request: IncomingMessage, response: ServerResponse, path: string): Promise<boolean> => {
    const match = CONNECTOR_SNAPSHOT_PATH.exec(path);
    if (match === null || request.method !== "POST") return false;
    const connectorId = match[1]!;
    const line = {
      operation: "", connectorId, status: 0, jobId: null as string | null, bindingId: null as string | null,
      bearer: request.headers.authorization === `Bearer ${FAKE_DASHBOARD_TOKEN}`,
      cookie: request.headers.cookie !== undefined,
    };
    snap.requests.push(line);
    const answer = (status: number, error: string) => {
      line.status = status;
      reject(response, status, error);
      return true;
    };
    // 서비스 Bearer 만 연다. 대시보드 쿠키만 있는 요청은 거절한다.
    if (!line.bearer) return answer(401, "Control Plane 토큰이 필요하다");

    let body: Json;
    try {
      body = JSON.parse(await readBody(request)) as Json;
    } catch {
      return answer(400, "snapshot 입력이 올바르지 않다");
    }
    if (!sameKeys(body, ["profile", "operation", "args"]) || typeof body.operation !== "string") {
      return answer(400, "profile, operation, args만 필요하다");
    }
    const operation = body.operation;
    line.operation = operation;
    const connector = catalogOf(state).find((candidate) => candidate.id === connectorId) as
      { snapshot?: { capabilities: Record<string, boolean> } } | undefined;
    if (connector === undefined) return answer(404, "없는 connector다");
    const capabilities = connector.snapshot?.capabilities;
    if (capabilities === undefined || !["compile", "start", "status"].includes(operation) || capabilities[operation] !== true) {
      return answer(409, "지원하지 않는 snapshot operation이다");
    }
    let stamp: Json | undefined;
    try {
      if (operation === "compile") {
        const args = body.args;
        if (!sameKeys(args, ["canonicalDraft", "resolvedAssets"]) || !Array.isArray(args.resolvedAssets)) throw new Error("invalid");
      } else {
        stamp = checkJobArgs(body.profile, operation, body.args);
        line.jobId = stamp.jobId as string;
        line.bindingId = stamp.bindingId as string;
      }
    } catch {
      return answer(400, "snapshot 입력이 올바르지 않다");
    }
    const profile = body.profile as string;
    if (state.boundConnectors.get(profile)?.has(connectorId) !== true) return answer(404, "설치하지 않은 connector다");

    const ok = (result: unknown) => {
      if (typeof result === "object" && result !== null && "error" in result) snap.errorBodies.push(JSON.stringify(result));
      line.status = 200;
      send(response, 200, { ok: true, result, capabilities });
      return true;
    };

    if (operation === "compile") {
      const args = body.args as { canonicalDraft: Json; resolvedAssets: Json[] };
      try {
        return ok(compile(args.canonicalDraft, args.resolvedAssets));
      } catch {
        // compile 의 isError 결과는 plugin 이 원문을 버리고 503 으로 끝낸다.
        return answer(503, "unavailable");
      }
    }

    const jobId = stamp!.jobId as string;
    if (operation === "start") {
      snap.starts.set(jobId, (snap.starts.get(jobId) ?? 0) + 1);
      const existing = snap.jobs.get(jobId);
      if (existing !== undefined) {
        // 이미 예약한 jobId 다. worker 를 다시 띄우지 않고 stamp 가 모두 같을 때만 그 상태를 돌려준다.
        if (JSON.stringify(existing.stamp) !== JSON.stringify(stamp)) return ok({ error: "invalid_input" });
        return ok({ job_id: jobId, status: existing.status, stamp, error_code: existing.errorCode });
      }
      const refused = snap.rejectNextStart;
      snap.rejectNextStart = undefined;
      snap.jobs.set(jobId, { stamp: stamp!, status: refused === undefined ? "running" : "failed", errorCode: refused ?? null });
      if (refused !== undefined) return ok({ job_id: jobId, status: "failed", stamp, error_code: refused });
      const lost = snap.loseNextStart;
      snap.loseNextStart = undefined;
      if (lost === "disconnect") {
        // 접수는 했지만 응답이 닿지 않는다.
        response.socket?.destroy();
        return true;
      }
      if (lost === "error") return answer(500, "unavailable");
      return ok({ job_id: jobId, status: "running", stamp, error_code: null });
    }

    // status 는 그 stamp 의 job 만 읽는다. 없는 job 에 worker 를 새로 띄우지 않는다.
    const job = snap.jobs.get(jobId);
    if (job === undefined || JSON.stringify(job.stamp) !== JSON.stringify(stamp)) return ok({ error: "not_found" });
    const outcome = snap.outcomes.get(jobId) ?? job.status;
    if (outcome === "not_found") return ok({ error: "not_found" });
    return ok(statusResult(stamp!, outcome, job.errorCode));
  };
}
