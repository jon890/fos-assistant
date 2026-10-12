import { expect, test } from "bun:test";
import {
  AUTHORIZE_URL_ENV,
  takeAuthorizer,
  TICKET_ENV,
} from "../src/content-snapshot-guard.ts";
import {
  snapshotStampSchema,
  type SnapshotStamp,
} from "../src/content-snapshot-job.ts";

const stamp: SnapshotStamp = {
  protocol: "content-snapshot-v1",
  snapshotId: "11111111-1111-4111-8111-111111111111",
  jobId: "22222222-2222-4222-8222-222222222222",
  snapshotHash: "a".repeat(64),
  bindingId: "41",
  connectionId: "42",
  bindingUpdatedAt: "2026-10-12T01:02:03.000000Z",
  connectionUpdatedAt: "2026-10-12T01:02:04.123000Z",
  profileHash: "b".repeat(64),
};

test("stamp 는 소수 여섯 자리를 유지하고 ticket 의 Instant.toString 시각은 받지 않는다", () => {
  expect(snapshotStampSchema.parse(stamp)).toEqual(stamp);
  for (const key of ["bindingUpdatedAt", "connectionUpdatedAt"]) {
    for (const value of [
      "2026-10-12T01:02:03Z",
      "2026-10-12T01:02:04.123Z",
      "2026-10-12T01:02:04.1230000Z",
    ])
      expect(
        snapshotStampSchema.safeParse({ ...stamp, [key]: value }).success,
      ).toBe(false);
  }
});

test("authorizer 는 ticket 안의 시각을 stamp decoder 에 넘기거나 다시 쓰지 않는다", async () => {
  // Java Instant.toString() 이 만드는 0자리·3자리 시각을 담은 합성 ticket 이다. 서명 검증은 CP 가 맡는다.
  const payload = {
    bindingUpdatedAt: "2026-10-12T01:02:03Z",
    connectionUpdatedAt: "2026-10-12T01:02:04.123Z",
  };
  const ticket = `${Buffer.from(JSON.stringify(payload)).toString("base64url")}.synthetic-signature`;
  const requests: unknown[] = [];
  const authorize = takeAuthorizer(
    {
      [TICKET_ENV]: ticket,
      [AUTHORIZE_URL_ENV]:
        "http://127.0.0.1:9/internal/content-draft-jobs/authorize",
    },
    async (_url, init) => {
      requests.push(JSON.parse(String(init.body)));
      return Response.json({ allowed: true });
    },
  );
  expect(authorize).not.toBeNull();
  await authorize!("start");
  await authorize!("photo", 0);
  await authorize!("save");
  expect(requests).toEqual([
    { ticket, stage: "start" },
    { ticket, stage: "photo", position: 0 },
    { ticket, stage: "save" },
  ]);
});
