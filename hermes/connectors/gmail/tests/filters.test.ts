import { describe, expect, test } from "bun:test";
import { createGmailServer } from "../src/server.ts";
import {
  FakeGoogle,
  accessToken,
  credentials,
  expectFailure,
  tool,
  withMcp,
} from "./support.ts";

const labels = [
  { id: "INBOX", name: "INBOX", type: "system" },
  { id: "STARRED", name: "STARRED", type: "system" },
  { id: "TRASH", name: "TRASH", type: "system" },
  { id: "SPAM", name: "SPAM", type: "system" },
  { id: "Label_7", name: "영수증", type: "user" },
  { id: "Label_8", name: "새 라벨", type: "user" },
];

function setup() {
  const fake = new FakeGoogle();
  fake.on("POST", "/token", { access_token: accessToken, expires_in: 3599 });
  fake.on("GET", "/gmail/labels", { labels });
  const server = createGmailServer({
    tokenUrl: `${fake.url}/token`,
    apiBase: `${fake.url}/gmail`,
    env: credentials,
  });
  return { fake, server };
}

describe("Gmail 라벨과 필터", () => {
  test("도구 목록은 새 여섯 도구와 기존 도구를 함께 MCP로 공개한다", async () => {
    const { fake, server } = setup();
    try {
      await withMcp(server, async (client) => {
        const tools = await client.listTools();
        expect(tools.tools.map((item) => item.name)).toEqual(
          expect.arrayContaining([
            "create_label",
            "update_label",
            "list_filters",
            "create_filter",
            "delete_filter",
            "apply_labels_to_query",
          ]),
        );
      });
    } finally {
      fake.stop();
    }
  });

  test("라벨 만들기와 수정은 이름, 색, 표시 설정을 정확히 Gmail 요청으로 바꾼다", async () => {
    const { fake, server } = setup();
    fake.on("POST", "/gmail/labels", { id: "Label_8", name: "광고" });
    fake.on("PATCH", "/gmail/labels/Label_8", {
      id: "Label_8",
      name: "읽을거리",
    });
    try {
      await withMcp(server, async (client) => {
        expect(
          (
            await tool(client, "create_label", {
              name: "광고",
              text_color: "#ffffff",
              background_color: "#000000",
              label_list_visibility: "labelShow",
              message_list_visibility: "show",
            })
          ).result.isError,
        ).not.toBe(true);
        expect(
          (
            await tool(client, "update_label", {
              label: "Label_8",
              name: "읽을거리",
              text_color: "#ffffff",
              background_color: "#000000",
            })
          ).result.isError,
        ).not.toBe(true);
      });
      expect(JSON.parse(fake.seen("POST", "/gmail/labels")[0]!.body)).toEqual({
        name: "광고",
        color: { textColor: "#ffffff", backgroundColor: "#000000" },
        labelListVisibility: "labelShow",
        messageListVisibility: "show",
      });
      expect(
        JSON.parse(fake.seen("PATCH", "/gmail/labels/Label_8")[0]!.body),
      ).toEqual({
        name: "읽을거리",
        color: { textColor: "#ffffff", backgroundColor: "#000000" },
      });
    } finally {
      fake.stop();
    }
  });

  test("라벨 이름은 225자까지이고 시스템 라벨은 만들거나 수정하지 않는다", async () => {
    const { fake, server } = setup();
    try {
      await withMcp(server, async (client) => {
        for (const args of [{ name: "x".repeat(226) }, { name: "INBOX" }])
          await expectFailure(
            client,
            "GMAIL_INVALID_INPUT",
            "create_label",
            args,
          );
        await expectFailure(client, "GMAIL_INVALID_INPUT", "update_label", {
          label: "INBOX",
          name: "다른 이름",
        });
        await expectFailure(client, "GMAIL_INVALID_INPUT", "create_label", {
          name: "색",
          text_color: "#fff",
        });
      });
      expect(fake.seen("POST", "/gmail/labels")).toHaveLength(0);
      expect(fake.seen("PATCH")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test.each([
    ["label_list_visibility", "show"],
    ["message_list_visibility", "labelShow"],
  ])("create_label은 잘못된 %s를 HTTP 전에 거절한다", async (field, value) => {
    const { fake, server } = setup();
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "GMAIL_INVALID_INPUT", "create_label", {
          name: "새 라벨",
          [field]: value,
        }),
      );
      expect(fake.seen("POST", "/gmail/labels")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test("CHAT 예약 라벨은 만들기 전에 거절한다", async () => {
    const { fake, server } = setup();
    try {
      await withMcp(server, (client) =>
        expectFailure(client, "GMAIL_INVALID_INPUT", "create_label", {
          name: "CHAT",
        }),
      );
      expect(fake.seen("POST", "/gmail/labels")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test("필터는 라벨 이름을 ID로 풀고 criteria와 action을 변경 없이 보낸다", async () => {
    const { fake, server } = setup();
    fake.on("POST", "/gmail/settings/filters", { id: "filter-1" });
    try {
      await withMcp(server, async (client) => {
        expect(
          (
            await tool(client, "create_filter", {
              from: "news@example.com",
              to: "me@example.com",
              subject: "소식",
              query: "newer_than:7d",
              negated_query: "label:spam",
              has_attachment: "true",
              size: "1048576",
              size_comparison: "larger",
              add_labels: "영수증, STARRED",
              remove_labels: "INBOX",
            })
          ).result.isError,
        ).not.toBe(true);
      });
      expect(
        JSON.parse(fake.seen("POST", "/gmail/settings/filters")[0]!.body),
      ).toEqual({
        criteria: {
          from: "news@example.com",
          to: "me@example.com",
          subject: "소식",
          query: "newer_than:7d",
          negatedQuery: "label:spam",
          hasAttachment: true,
          size: 1048576,
          sizeComparison: "larger",
        },
        action: {
          addLabelIds: ["Label_7", "STARRED"],
          removeLabelIds: ["INBOX"],
        },
      });
    } finally {
      fake.stop();
    }
  });

  test("전달, 휴지통, 스팸, 잘못된 크기와 boolean은 필터 HTTP 호출 전에 거절한다", async () => {
    const { fake, server } = setup();
    try {
      await withMcp(server, async (client) => {
        for (const args of [
          { forward: "elsewhere@example.com" },
          { add_labels: "TRASH" },
          { remove_labels: "SPAM" },
          { has_attachment: "yes" },
          { size: "1.5" },
          { size_comparison: "medium" },
        ])
          await expectFailure(
            client,
            "GMAIL_INVALID_INPUT",
            "create_filter",
            args,
          );
      });
      expect(fake.seen("POST", "/gmail/settings/filters")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test.each([{ size_comparison: "larger" }, { size_comparison: "smaller" }])(
    "size 없이 size_comparison만 준 필터는 HTTP 전에 거절한다",
    async (arguments_) => {
      const { fake, server } = setup();
      try {
        await withMcp(server, (client) =>
          expectFailure(
            client,
            "GMAIL_INVALID_INPUT",
            "create_filter",
            arguments_,
          ),
        );
        expect(fake.seen("POST", "/gmail/settings/filters")).toHaveLength(0);
      } finally {
        fake.stop();
      }
    },
  );

  test("필터 목록과 삭제는 Gmail settings endpoint만 사용하고 빈 성공 응답도 성공이다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/settings/filters", { filter: [{ id: "filter-1" }] });
    fake.routes.set(
      "DELETE /gmail/settings/filters/filter-1",
      new Response(null, { status: 204 }),
    );
    try {
      await withMcp(server, async (client) => {
        expect((await tool(client, "list_filters")).body).toEqual({
          filters: [{ id: "filter-1" }],
        });
        expect(
          (await tool(client, "delete_filter", { filter_id: "filter-1" }))
            .result.isError,
        ).not.toBe(true);
      });
    } finally {
      fake.stop();
    }
  });

  test("필터 scope의 403만 재발급 안내 오류 코드로 바꾸고 라벨은 기존 token으로 동작한다", async () => {
    const { fake, server } = setup();
    fake.on(
      "GET",
      "/gmail/settings/filters",
      { error: { message: "forbidden" } },
      403,
    );
    try {
      await withMcp(server, async (client) => {
        await expectFailure(
          client,
          "GMAIL_FILTER_SCOPE_REQUIRED",
          "list_filters",
        );
        expect((await tool(client, "list_labels")).result.isError).not.toBe(
          true,
        );
      });
    } finally {
      fake.stop();
    }
  });

  test("기존 메일 적용은 다중 페이지에서 전부 찾되 500개 이하여야 정확한 승인 수와 일치한다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/messages", {
      messages: [{ id: "m1" }, { id: "m2" }],
      nextPageToken: "second",
    });
    fake.routes.set("GET /gmail/messages", (request) =>
      request.query.get("pageToken")
        ? new Response(JSON.stringify({ messages: [{ id: "m3" }] }))
        : new Response(
            JSON.stringify({
              messages: [{ id: "m1" }, { id: "m2" }],
              nextPageToken: "second",
            }),
          ),
    );
    fake.routes.set(
      "POST /gmail/messages/batchModify",
      new Response(null, { status: 204 }),
    );
    try {
      await withMcp(server, async (client) => {
        const result = await tool(client, "apply_labels_to_query", {
          query: "from:news@example.com",
          add_labels: "영수증",
          remove_labels: "INBOX",
          expected_count: "3",
        });
        expect(result.result.isError).not.toBe(true);
      });
      expect(
        JSON.parse(fake.seen("POST", "/gmail/messages/batchModify")[0]!.body),
      ).toEqual({
        ids: ["m1", "m2", "m3"],
        addLabelIds: ["Label_7"],
        removeLabelIds: ["INBOX"],
      });
    } finally {
      fake.stop();
    }
  });

  test("기존 메일 대상 수가 달라지거나 501개면 쓰지 않고 실제 수를 돌려준다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/messages", { messages: [{ id: "m1" }] });
    try {
      await withMcp(server, async (client) => {
        const { result, body } = await tool(client, "apply_labels_to_query", {
          query: "x",
          add_labels: "영수증",
          expected_count: "2",
        });
        expect(result.isError).toBe(true);
        expect(body).toEqual({
          error: { code: "GMAIL_TARGET_COUNT_CHANGED", actual_count: 1 },
        });
      });
      expect(fake.seen("POST", "/gmail/messages/batchModify")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test("정확히 500개는 한 번의 batchModify로 적용하고 501개는 승인 수가 맞아도 쓰지 않는다", async () => {
    const ids = Array.from({ length: 500 }, (_, index) => ({
      id: `m${index}`,
    }));
    const exact = setup();
    exact.fake.on("GET", "/gmail/messages", { messages: ids });
    exact.fake.routes.set(
      "POST /gmail/messages/batchModify",
      new Response(null, { status: 204 }),
    );
    try {
      await withMcp(exact.server, async (client) => {
        expect(
          (
            await tool(client, "apply_labels_to_query", {
              query: "has:attachment",
              add_labels: "영수증",
              expected_count: "500",
            })
          ).result.isError,
        ).not.toBe(true);
      });
      expect(
        JSON.parse(
          exact.fake.seen("POST", "/gmail/messages/batchModify")[0]!.body,
        ).ids,
      ).toHaveLength(500);
    } finally {
      exact.fake.stop();
    }

    const over = setup();
    over.fake.on("GET", "/gmail/messages", {
      messages: [...ids, { id: "m500" }],
    });
    try {
      await withMcp(over.server, async (client) => {
        const { result, body } = await tool(client, "apply_labels_to_query", {
          query: "has:attachment",
          add_labels: "영수증",
          expected_count: "500",
        });
        expect(result.isError).toBe(true);
        expect(body).toEqual({ error: { code: "GMAIL_INVALID_INPUT" } });
      });
      expect(
        over.fake.seen("POST", "/gmail/messages/batchModify"),
      ).toHaveLength(0);
    } finally {
      over.fake.stop();
    }
  });

  test("라벨 별칭이 실제 TRASH나 SPAM ID를 가리켜도 필터와 일괄 적용을 쓰기 전에 막는다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/gmail/labels", {
      labels: [...labels, { id: "TRASH", name: "보관함", type: "user" }],
    });
    try {
      await withMcp(server, async (client) => {
        await expectFailure(client, "GMAIL_INVALID_INPUT", "create_filter", {
          add_labels: "보관함",
        });
        await expectFailure(
          client,
          "GMAIL_INVALID_INPUT",
          "apply_labels_to_query",
          { query: "newer_than:1d", add_labels: "보관함", expected_count: "0" },
        );
        await expectFailure(
          client,
          "GMAIL_INVALID_INPUT",
          "apply_labels_to_query",
          { query: "x", add_labels: "영수증", expected_count: "501" },
        );
      });
      expect(fake.seen("POST", "/gmail/settings/filters")).toHaveLength(0);
      expect(fake.seen("POST", "/gmail/messages/batchModify")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });
});
