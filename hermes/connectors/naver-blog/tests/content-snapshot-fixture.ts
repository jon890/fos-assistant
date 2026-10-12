import { createHash, randomUUID } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { compileContentDraft } from "../src/content-compiler.ts";
import type {
  CanonicalBlock,
  CompiledSnapshotV1,
} from "../src/content-draft.ts";
import { AUTHORIZE_PATH } from "../src/content-snapshot-guard.ts";
import type { SnapshotStamp } from "../src/content-snapshot-job.ts";

/** 서로 다른 바이트의 합성 JPEG. 실제 사진이나 계정 자료가 아니다. */
export const syntheticJpeg = (seed: number) =>
  new Uint8Array([
    0xff,
    0xd8,
    0xff,
    0xe0,
    seed & 0xff,
    (seed >> 8) & 0xff,
    7,
    7,
    7,
  ]);
export const sha256 = (bytes: Uint8Array | string) =>
  createHash("sha256").update(bytes).digest("hex");

/**
 * `photoDir` 에 `count` 장의 서로 다른 합성 사진을 쓰고 `order` 의 asset 순서로 원고를 compile 한다.
 * `order` 는 1부터의 asset 번호다. 같은 번호를 반복하면 같은 asset 을 여러 자리에 둔다.
 */
export async function syntheticSnapshot(
  photoDir: string,
  count: number,
  order: number[] = Array.from({ length: count }, (_, index) => index + 1),
): Promise<CompiledSnapshotV1> {
  await mkdir(photoDir, { recursive: true });
  const assets = [];
  for (let index = 1; index <= count; index++) {
    const bytes = syntheticJpeg(index);
    await writeFile(join(photoDir, `${index}.jpg`), bytes);
    assets.push({
      assetId: String(1000 + index),
      sourceFingerprint: sha256(bytes),
      fileName: `${index}.jpg`,
      photoDir,
      ordinal: index,
    });
  }
  const blocks: CanonicalBlock[] = [
    { type: "paragraph", text: "합성 원고 첫 줄" },
  ];
  for (const number of order)
    blocks.push({ type: "image", assetId: String(1000 + number) });
  return compileContentDraft(
    {
      schemaVersion: 1,
      draftId: "11111111-1111-4111-8111-111111111111",
      revision: 3,
      conversationId: "22222222-2222-4222-8222-222222222222",
      title: "합성 제목",
      category: "가상국수로그",
      tags: ["점심"],
      blocks,
    },
    assets,
  );
}

export function stampFor(
  snapshot: CompiledSnapshotV1,
  jobId = randomUUID(),
): SnapshotStamp {
  return {
    protocol: "content-snapshot-v1",
    snapshotId: randomUUID(),
    jobId,
    snapshotHash: snapshot.snapshotHash,
    bindingId: "41",
    connectionId: "42",
    bindingUpdatedAt: "2026-10-12T01:02:03.123456Z",
    connectionUpdatedAt: "2026-10-12T01:02:04.000001Z",
    profileHash: sha256("synthetic-profile"),
  };
}

export type AuthorizeCall = {
  ticket: string;
  stage: string;
  position?: number;
};
type Decision = "allow" | "deny" | Response;

/** loopback 의 대역 authorize 경로. 받은 요청을 순서대로 남기고 `decide` 가 답을 정한다. */
export function fakeControlPlane(
  decide: (call: AuthorizeCall) => Decision = () => "allow",
) {
  const calls: AuthorizeCall[] = [];
  const server = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    async fetch(request) {
      if (
        request.method !== "POST" ||
        new URL(request.url).pathname !== AUTHORIZE_PATH
      )
        return new Response("", { status: 404 });
      const call = (await request.json()) as AuthorizeCall;
      calls.push(call);
      const decision = decide(call);
      if (decision === "allow") return Response.json({ allowed: true });
      if (decision === "deny")
        return Response.json({ error: "denied" }, { status: 403 });
      return decision;
    },
  });
  return {
    url: `http://127.0.0.1:${server.port}${AUTHORIZE_PATH}`,
    calls,
    stop: () => server.stop(true),
  };
}
