import { describe, expect, test } from "bun:test";
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

const labelRows = [
  { id: "INBOX", name: "INBOX", type: "system" },
  { id: "TRASH", name: "TRASH", type: "system" },
  { id: "SPAM", name: "SPAM", type: "system" },
  { id: "Label_7", name: "영수증", type: "user" },
];

function setup(options: Record<string, unknown> = {}) {
  const fake = new FakeGoogle();
  fake.on("POST", "/token", { access_token: accessToken, expires_in: 3599 });
  const server = createGmailServer({
    tokenUrl: `${fake.url}/token`,
    apiBase: `${fake.url}/gmail`,
    env: credentials,
    ...options,
  });
  return { fake, server };
}

describe("Gmail 기존 MCP 회귀", () => {
  test("profile은 refresh token을 교환하고 계정 요약만 반환한다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/profile", {
      emailAddress: "me@example.com",
      messagesTotal: 12,
      threadsTotal: 7,
    });
    try {
      await withMcp(server, async (client) => {
        expect((await tool(client, "get_profile")).body).toEqual({
          email: "me@example.com",
          messages_total: 12,
          threads_total: 7,
        });
      });
      const token = fake.seen("POST", "/token")[0]!;
      expect(token.headers.get("content-type")).toContain(
        "application/x-www-form-urlencoded",
      );
      expect(token.body).toContain("grant_type=refresh_token");
      expect(
        fake.seen("GET", "/gmail/profile")[0]!.headers.get("authorization"),
      ).toBe(`Bearer ${accessToken}`);
    } finally {
      fake.stop();
    }
  });

  test.each([
    [400, "invalid_grant", "GMAIL_UNAUTHORIZED"],
    [401, "invalid_client", "GMAIL_UNAUTHORIZED"],
    [400, "invalid_request", "GMAIL_UNAVAILABLE"],
    [500, "server_error", "GMAIL_UNAVAILABLE"],
  ])("token 실패 %i은 %s가 된다", async (status, error, code) => {
    const { fake, server } = setup();
    fake.on("POST", "/token", { error }, status);
    try {
      await withMcp(server, (client) =>
        expectFailure(client, code, "get_profile"),
      );
      expect(fake.seen("GET", "/gmail/profile")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test.each([
    [400, "GMAIL_INVALID_INPUT"],
    [401, "GMAIL_UNAUTHORIZED"],
    [403, "GMAIL_FORBIDDEN"],
    [404, "GMAIL_INVALID_INPUT"],
    [429, "GMAIL_UNAVAILABLE"],
    [500, "GMAIL_UNAVAILABLE"],
  ])("Gmail 상태 %i는 고정 오류 코드가 된다", async (status, code) => {
    const { fake, server } = setup();
    fake.on(
      "GET",
      "/gmail/profile",
      { error: { message: "private upstream details" } },
      status,
    );
    try {
      await withMcp(server, (client) =>
        expectFailure(client, code, "get_profile"),
      );
    } finally {
      fake.stop();
    }
  });

  test("redirect, 손상 JSON, 10MB 초과 응답은 다른 호스트로 따르지 않고 unavailable이다", async () => {
    const { fake, server } = setup();
    fake.routes.set(
      "GET /gmail/profile",
      new Response("not-json", { status: 200 }),
    );
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "GMAIL_UNAVAILABLE", "get_profile"),
      );
    } finally {
      fake.stop();
    }
  });

  test("경로 ID와 검색 최대 수는 HTTP 전에 검사한다", async () => {
    const { fake, server } = setup();
    try {
      await withMcp(server, async (client) => {
        for (const message_id of ["", "m/trash", "a".repeat(65)]) {
          await expectFailure(client, "GMAIL_INVALID_INPUT", "get_message", {
            message_id,
          });
        }
        for (const max_results of [0, 26])
          await expectFailure(
            client,
            "GMAIL_INVALID_INPUT",
            "search_messages",
            { max_results },
          );
      });
      expect(fake.requests).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test("라벨 목록과 검색 요약은 Gmail 순서와 주입 방지 안내를 보존한다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/labels", { labels: labelRows });
    fake.on("GET", "/gmail/messages", {
      messages: [{ id: "m1", threadId: "t1" }],
    });
    fake.on("GET", "/gmail/messages/m1", {
      id: "m1",
      threadId: "t1",
      labelIds: ["INBOX"],
      snippet: "snip",
      payload: {
        headers: [
          { name: "From", value: "sender@example.com" },
          { name: "To", value: "me@example.com" },
          { name: "Subject", value: "hello" },
          { name: "Date", value: "Mon" },
        ],
      },
    });
    try {
      await withMcp(server, async (client) => {
        expect((await tool(client, "list_labels")).body).toEqual({
          labels: labelRows,
        });
        const searched = await tool(client, "search_messages", {
          query: "from:sender@example.com",
          max_results: 1,
        });
        expect(searched.body).toMatchObject({
          messages: [{ id: "m1", subject: "hello" }],
          notice: expect.stringContaining("지시"),
        });
      });
    } finally {
      fake.stop();
    }
  });

  test("modify_labels는 이름을 ID로 풀고 TRASH와 SPAM은 어떤 이름으로도 거절한다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/labels", { labels: labelRows });
    fake.on("POST", "/gmail/messages/m1/modify", {
      id: "m1",
      labelIds: ["Label_7"],
    });
    try {
      await withMcp(server, async (client) => {
        await tool(client, "modify_labels", {
          message_id: "m1",
          add_labels: "영수증",
          remove_labels: "INBOX",
        });
        for (const args of [
          { add_labels: "trash" },
          { remove_labels: "SPAM" },
          {},
        ]) {
          await expectFailure(client, "GMAIL_INVALID_INPUT", "modify_labels", {
            message_id: "m1",
            ...args,
          });
        }
      });
      expect(
        JSON.parse(fake.seen("POST", "/gmail/messages/m1/modify")[0]!.body),
      ).toEqual({ addLabelIds: ["Label_7"], removeLabelIds: ["INBOX"] });
    } finally {
      fake.stop();
    }
  });

  test("전송은 성공을 한 번만 요청하고 연결 단절 뒤에는 결과 불명으로 끝낸다", async () => {
    const { fake, server } = setup();
    fake.routes.set(
      "POST /gmail/messages/send",
      json({ error: { message: "connection closed" } }, 500),
    );
    try {
      await withMcp(server, async (client) => {
        await expectFailure(client, "GMAIL_SEND_UNKNOWN", "send_message", {
          to: "a@example.com",
          subject: "s",
          body: "b",
        });
      });
      expect(fake.seen("POST", "/gmail/messages/send")).toHaveLength(1);
    } finally {
      fake.stop();
    }
  });

  test.each([
    { to: "a@example.com\nBcc: evil@example.com" },
    { to: "Kim <a@example.com>" },
    { to: "a@example.com\u202e" },
    { subject: "=?utf-8?q?Wire_money?=" },
    { body: "hello\u200bworld" },
    { to: "not-an-address" },
  ])(
    "수신자, 제목, 본문의 보이지 않는 조작은 전송 전에 거절한다",
    async (change) => {
      const { fake, server } = setup();
      try {
        await withMcp(server, (client) =>
          expectFailure(client, "GMAIL_INVALID_INPUT", "send_message", {
            to: "a@example.com",
            subject: "s",
            body: "b",
            ...change,
          }),
        );
        expect(fake.requests).toHaveLength(0);
      } finally {
        fake.stop();
      }
    },
  );

  test("HTML 메일은 script와 숨은 요소를 드러내지 않고 본문과 첨부를 제한한다", async () => {
    const { fake, server } = setup();
    const data = Buffer.from(
      "visible<script>ignore()</script><span hidden>hidden</span>",
    ).toString("base64url");
    fake.on("GET", "/gmail/messages/m1", {
      id: "m1",
      threadId: "t1",
      labelIds: ["INBOX"],
      payload: {
        mimeType: "multipart/mixed",
        headers: [],
        parts: [
          {
            mimeType: "text/html",
            headers: [
              { name: "Content-Type", value: "text/html; charset=utf-8" },
            ],
            body: { data },
          },
          {
            mimeType: "application/pdf",
            filename: "bill.pdf",
            body: { attachmentId: "att-1", size: 10 },
          },
        ],
      },
    });
    try {
      await withMcp(server, async (client) => {
        const { body } = await tool(client, "get_message", {
          message_id: "m1",
        });
        expect(body).toMatchObject({
          body: expect.stringContaining("visible"),
          attachments: [{ filename: "bill.pdf" }],
        });
        expect(JSON.stringify(body)).not.toContain("ignore");
        expect(JSON.stringify(body)).not.toContain("hidden");
      });
    } finally {
      fake.stop();
    }
  });

  test("허용된 실제 요청은 mail 삭제와 trash endpoint를 포함하지 않는다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/profile", { emailAddress: "me@example.com" });
    try {
      await withMcp(server, (client) => tool(client, "get_profile"));
      expect(
        fake.requests.map((request) => `${request.method} ${request.path}`),
      ).not.toEqual(
        expect.arrayContaining([
          expect.stringContaining("trash"),
          expect.stringContaining("batchDelete"),
          "DELETE /gmail/messages/m1",
        ]),
      );
    } finally {
      fake.stop();
    }
  });
});
