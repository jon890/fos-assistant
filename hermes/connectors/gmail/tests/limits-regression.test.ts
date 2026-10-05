import { expect, test } from "bun:test";
import { createGmailServer } from "../src/server.ts";
import {
  FakeGoogle,
  accessToken,
  credentials,
  expectFailure,
  json,
  tool,
  withMcp,
} from "./support.ts";

function setup(timeoutMs?: number) {
  const fake = new FakeGoogle();
  fake.on("POST", "/token", { access_token: accessToken });
  return {
    fake,
    server: createGmailServer({
      tokenUrl: `${fake.url}/token`,
      apiBase: `${fake.url}/gmail`,
      env: credentials,
      timeoutMs,
    }),
  };
}

test("1000자 header와 50개·255자 첨부 경계는 넘지 않고 정확히 유지한다", async () => {
  const { fake, server } = setup();
  const attachments = Array.from({ length: 51 }, (_, index) => ({
    mimeType: "application/pdf",
    filename: `${index}`.padEnd(300, "x"),
    body: { attachmentId: `a${index}`, size: index },
  }));
  fake.on("GET", "/gmail/messages/m1", {
    id: "m1",
    threadId: "t1",
    payload: {
      mimeType: "multipart/mixed",
      headers: [{ name: "Subject", value: "s".repeat(1001) }],
      parts: attachments,
    },
  });
  try {
    await withMcp(server, async (client) => {
      const { body } = await tool(client, "get_message", { message_id: "m1" });
      expect(body.subject as string).toHaveLength(1000);
      expect(body.attachments).toHaveLength(50);
      expect(
        (body.attachments as Array<{ filename: string }>)[0]!.filename,
      ).toHaveLength(255);
    });
  } finally {
    fake.stop();
  }
});

test("알 수 없는 charset과 padding 없는 base64url은 UTF-8 대체 본문으로 읽는다", async () => {
  const { fake, server } = setup();
  fake.on("GET", "/gmail/messages/m1", {
    id: "m1",
    payload: {
      mimeType: "text/plain",
      headers: [
        { name: "Content-Type", value: "text/plain; charset=no-such-charset" },
      ],
      body: { data: Buffer.from("한글").toString("base64url") },
    },
  });
  try {
    await withMcp(server, async (client) =>
      expect(
        (await tool(client, "get_message", { message_id: "m1" })).body,
      ).toMatchObject({ body: "한글" }),
    );
  } finally {
    fake.stop();
  }
});

test("10MB 응답, null·array·손상 JSON은 읽기 성공으로 가장하지 않는다", async () => {
  for (const response of [
    new Response("x".repeat(10 * 1024 * 1024 + 1), {
      headers: { "content-type": "application/json" },
    }),
    json(null),
    json(["not", "an", "object"]),
    new Response("not-json", {
      headers: { "content-type": "application/json" },
    }),
  ]) {
    const { fake, server } = setup();
    fake.routes.set("GET /gmail/profile", response);
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "GMAIL_UNAVAILABLE", "get_profile"),
      );
    } finally {
      fake.stop();
    }
  }
});

test("timeout은 재시도하지 않고 send만 결과 불명으로 분류한다", async () => {
  const { fake, server } = setup(10);
  fake.routes.set("GET /gmail/profile", async () => {
    await Bun.sleep(100);
    return json({ emailAddress: "late" });
  });
  try {
    await withMcp(server, (client) =>
      expectFailure(client, "GMAIL_UNAVAILABLE", "get_profile"),
    );
    expect(fake.seen("GET", "/gmail/profile")).toHaveLength(1);
  } finally {
    fake.stop();
  }
});

test.each([
  "a@example.com,",
  "=?utf-8?b?YkBldmlsLmV4YW1wbGU=?= <a@example.com>",
  "a@example.com\u200b",
  "a@example.com\u2066",
])(
  "encoded·invisible·끝 쉼표 수신자는 raw header 전에 거절한다: %s",
  async (to) => {
    const { fake, server } = setup();
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "GMAIL_INVALID_INPUT", "send_message", {
          to,
          subject: "제목",
          body: "본문",
        }),
      );
      expect(fake.requests).toHaveLength(0);
    } finally {
      fake.stop();
    }
  },
);

test.each(["a\u034fb", "a\ufe00b", "a\u{e0100}b", "a\u200db"])(
  "invisible mark subject/body는 거절하고 허용 ZWJ 그림문자는 보존한다: %s",
  async (subject) => {
    const { fake, server } = setup();
    fake.on("POST", "/gmail/messages/send", { id: "sent", threadId: "t" });
    try {
      await withMcp(server, async (client) => {
        if (subject === "a\u200db") {
          expect(
            (
              await tool(client, "send_message", {
                to: "a@example.com",
                subject: "제목",
                body: "가족 👨‍👩‍👧",
              })
            ).result.isError,
          ).not.toBe(true);
        } else
          await expectFailure(client, "GMAIL_INVALID_INPUT", "send_message", {
            to: "a@example.com",
            subject,
            body: "본문",
          });
      });
    } finally {
      fake.stop();
    }
  },
);
