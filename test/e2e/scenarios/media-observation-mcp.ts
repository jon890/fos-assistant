/** 실제 대화 실행의 서명으로 관찰 REST와 MCP를 왕복한다. 합성 사진만 쓰고 provider를 호출하지 않는다. */
import { randomUUID } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  createMediaFixture,
  requireSourceByteLength,
} from "../../support/media-pipeline-fixture.ts";
import {
  inspectSubmittedMediaBatch,
  validateMediaMapping,
  type ExpectedMediaMapping,
} from "../../support/media-pipeline-mapping.ts";
import {
  call,
  expect,
  expectStatus,
  fail,
  step,
  upload,
  type Scenario,
} from "../harness.ts";
import {
  callTool,
  contextFor,
  openStream,
  within,
  type ToolResult,
} from "../delegation-support.ts";
import {
  signedCallContext,
  signedSubagentRegistration,
} from "../mcp-context.ts";
import { NEW_PERSON } from "./people.ts";

export const MEDIA_OBSERVATION_PROFILE = "media-observation-mcp";
const AGENT = "media-observation-mcp";
const LIST = "list_media_observations";
const RECORD = "record_media_observation";
type Observation = {
  assetId: string;
  ordinal: number;
  sourceFingerprint: string;
  status: string;
  revision: number;
  sourceAssurance: string | null;
  observation: { summary: string; coverage: { mode: string; frame?: number } };
  provenance: { provider: string; model: string };
};
type Page = { items: Observation[]; nextAfterAssetId: string | null };

function data<T>(result: ToolResult): T {
  expect(!result.isError, `관찰 도구가 실패했다: ${result.text}`);
  const begin = "\n<external-data>\n";
  expect(
    result.text.includes(begin) && result.text.endsWith("</external-data>"),
    "관찰이 외부 데이터로 감싸지지 않았다",
  );
  return JSON.parse(
    result.text.slice(
      result.text.indexOf(begin) + begin.length,
      result.text.lastIndexOf("</external-data>"),
    ),
  ) as T;
}

export const mediaObservationMcpScenario: Scenario = {
  name: "사진 관찰 REST와 서명된 MCP",
  async run(context) {
    // 앞 사람 추가 시나리오에서 실제 허용 목록에 등록한 사용자의 권한을 쓴다.
    const ownerToken = context.tokens.aunt;
    const ownerContext = {
      ...context,
      tokens: { ...context.tokens, dad: ownerToken },
    };
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: AGENT,
          name: "사진 관찰 검사",
          hermesProfile: MEDIA_OBSERVATION_PROFILE,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${MEDIA_OBSERVATION_PROFILE}`,
          costMode: "SUBSCRIPTION",
          credentialScope: "DEDICATED",
          visibility: "PRIVATE",
          ownerEmail: NEW_PERSON.email,
        },
      }),
      200,
      "관찰 에이전트 등록",
    );
    let issued: { id: number; token: string } | undefined;
    let conversationId: string | undefined;
    let completed: Promise<unknown> | undefined;
    let failed = false;
    const fixtureDir = mkdtempSync(join(tmpdir(), "media30-cp-"));
    try {
      issued = expectStatus(
        await call(context, "/admin/agent-tokens", {
          method: "POST",
          token: context.tokens.dad,
          body: {
            profileName: MEDIA_OBSERVATION_PROFILE,
            label: "observation-e2e",
          },
        }),
        200,
        "관찰 토큰 발급",
      ).json();
      const token = issued!.token;
      conversationId = expectStatus(
        await call(context, "/chat/conversations", {
          method: "POST",
          token: ownerToken,
          body: { agentCode: AGENT },
        }),
        200,
        "관찰 대화 시작",
      ).json<{ conversationId: string }>().conversationId;
      const ids: number[] = [];
      const fixtures = createMediaFixture(fixtureDir, 30);
      expect(
        fixtures.every((item) => item.actuallyObserved === "PASS"),
        "원본 픽셀 ID가 다르다",
      );
      expect(
        new Set(fixtures.map((item) => item.bytesSha256)).size === 30,
        "원본 SHA가 중복된다",
      );
      expect(
        new Set(fixtures.map((item) => item.expectedMarker)).size === 30,
        "원본 표식이 중복된다",
      );
      for (const fixture of fixtures) {
        const bytes = readFileSync(join(fixtureDir, fixture.fileName!));
        requireSourceByteLength(bytes, fixture.byteLength);
        ids.push(
          expectStatus(
            await upload(
              context,
              `/chat/conversations/${conversationId}/attachments`,
              {
                token: ownerToken,
                field: "file",
                fileName: fixture.fileName!,
                contentType: `image/${fixture.format.toLowerCase()}`,
                bytes,
              },
            ),
            200,
            "합성 사진 업로드",
          ).json<{ id: number }>().id,
        );
      }
      const sent = [...fixtures].reverse();
      const sentIds = [...ids].reverse();
      const expected: ExpectedMediaMapping[] = sent.map(
        (fixture, position) => ({
          assetKey: fixture.assetKey,
          assetId: String(sentIds[position]),
          position,
          ordinal: position + 1,
          expectedMarker: fixture.expectedMarker,
          sourceSha256: fixture.bytesSha256!,
          orientation: fixture.orientation!,
        }),
      );
      step(
        "실제 메시지에 사진을 붙이고 실행을 유지해 현재 session으로 관찰을 저장한다",
      );
      context.hermes.holdNextRun();
      const firstTurn = call(context, "/chat/messages", {
        method: "POST",
        token: ownerToken,
        body: {
          conversationId,
          text: "합성 사진 관찰 검사",
          agentCode: AGENT,
          attachmentIds: sentIds,
        },
      });
      firstTurn.catch(() => undefined);
      completed = firstTurn;
      await within(
        Promise.race([
          context.hermes.waitForHeldRun(),
          firstTurn.then((response) => {
            expectStatus(response, 200, "사진을 붙인 관찰 실행 시작");
            fail("유지해야 할 관찰 실행이 먼저 끝났다");
          }),
        ]),
        5_000,
        "관찰 실행을 받지 못했다",
      );
      const session = context.hermes.heldRun()?.sessionId;
      const firstRunId = context.hermes.heldRun()?.runId;
      if (session === undefined) fail("관찰 실행 session이 없다");
      expect(
        context.hermes.lastSubmittedImages().length === ids.length,
        "기존 native 이미지 입력이 사라졌다",
      );
      const listAll = async (activeSession: string): Promise<Observation[]> => {
        const items: Observation[] = [];
        let cursor: string | null = null;
        let pages = 0;
        do {
          const page: Page = data(
            await callTool(
              context,
              token,
              LIST,
              { afterAssetId: cursor, limit: 7 },
              contextFor(token, LIST, activeSession),
            ),
          );
          expect(
            page.items.length > 0 && page.items.length <= 7,
            "관찰 페이지 크기가 다르다",
          );
          expect(
            page.items.reduce(
              (bytes, item) =>
                bytes + Buffer.byteLength(JSON.stringify(item.observation)),
              0,
            ) <= 32768,
            "페이지 본문 예산을 넘었다",
          );
          items.push(...page.items);
          cursor = page.nextAfterAssetId;
          expect(++pages <= 30, "관찰 커서가 끝나지 않는다");
        } while (cursor !== null);
        expect(
          items.length === 30 &&
            new Set(items.map((item) => item.assetId)).size === 30,
          "관찰 30장의 대응이 빠지거나 중복된다",
        );
        return items;
      };
      const listed = await listAll(session);
      const received = inspectSubmittedMediaBatch(
        context.hermes.lastSubmittedImages(),
        expected.map((item) => item.orientation),
      );
      const mapping = validateMediaMapping(expected, listed, received);
      const swapped = [...received];
      [swapped[0], swapped[1]] = [
        { ...received[1], label: received[0].label },
        { ...received[0], label: received[1].label },
      ];
      let crosslinked = false;
      try {
        validateMediaMapping(expected, listed, swapped);
      } catch (error) {
        crosslinked =
          error instanceof Error && error.message === "MARKER_MISMATCH";
      }
      expect(crosslinked, "검증기가 같은 대화의 교차 연결 픽셀을 놓쳤다");
      const savedRows: Observation[] = [];
      const record = async (
        assetId: number,
        revision: number,
        summary: string,
        requestId = randomUUID(),
      ) =>
        callTool(
          context,
          token,
          RECORD,
          {
            assetId: String(assetId),
            expectedRevision: revision,
            requestId,
            observation: {
              status: "NEEDS_REVIEW",
              summary,
              coverage:
                sent[sentIds.indexOf(assetId)].format === "GIF"
                  ? { mode: "FIRST_FRAME", frame: 0 }
                  : { mode: "OVERVIEW" },
            },
          },
          contextFor(token, RECORD, session),
        );
      for (const row of mapping.rows) {
        const summary = `합성 픽셀 ID ${row.recoveredId}`;
        const saved = data<Observation>(
          await record(Number(row.assetId), 0, summary),
        );
        expect(
          saved.assetId === row.assetId &&
            saved.ordinal === row.ordinal &&
            saved.sourceFingerprint === row.sourceSha256,
          "저장 응답의 첨부·순서·원본 SHA가 다르다",
        );
        expect(
          saved.status === "NEEDS_REVIEW" &&
            saved.observation.summary === summary,
          "저장 상태 또는 합성 본문이 다르다",
        );
        const gif = sent[row.position].format === "GIF";
        expect(
          saved.observation.coverage.mode ===
            (gif ? "FIRST_FRAME" : "OVERVIEW") &&
            (!gif || saved.observation.coverage.frame === 0),
          "준비 이미지 판독 범위를 과장했다",
        );
        expect(
          saved.revision === 1 && saved.sourceAssurance === "MODEL_UNVERIFIED",
          "모델 관찰의 revision 또는 assurance가 다르다",
        );
        expect(
          saved.provenance.provider === "UNKNOWN" &&
            saved.provenance.model === "UNKNOWN",
          "요청 모델을 실제 신원으로 기록했다",
        );
        savedRows.push(saved);
      }
      const native = `native-${randomUUID()}`;
      const registered = await fetch(
        `${context.api.replace(/\/api\/v1$/, "")}/internal/hermes/session-bindings/subagent`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(
            signedSubagentRegistration(token, session, session, native),
          ),
        },
      );
      expect(registered.status === 201, "native 자식 등록이 실패했다");
      context.hermes.releaseHeldRun();
      expectStatus(await firstTurn, 200, "첫 관찰 turn 완료");
      const nativeResult = await callTool(
        context,
        token,
        LIST,
        {},
        signedCallContext(token, LIST, session, native, `call_${randomUUID()}`),
      );
      expect(
        data<Page>(nativeResult).items.length > 0,
        "끝난 부모의 등록된 자식이 관찰을 읽지 못했다",
      );

      step(
        "뒤 turn에서 관찰을 다시 읽고 REST USER 정정은 MODEL이 덮어쓰지 못한다",
      );
      context.hermes.holdNextRun();
      const turn = await openStream(
        ownerContext,
        "관찰을 다시 읽어 줘",
        AGENT,
        conversationId,
      );
      completed = turn.completed;
      await within(
        context.hermes.waitForHeldRun(),
        5_000,
        "둘째 관찰 실행을 받지 못했다",
      );
      const nextSession = context.hermes.heldRun()?.sessionId;
      if (nextSession === undefined) fail("둘째 session이 없다");
      expect(
        context.hermes.heldRun()?.runId !== firstRunId,
        "새 turn이 같은 실행을 썼다",
      );
      expect(
        context.hermes.lastSubmittedImages().length === 0,
        "사진 없는 turn에 이미지를 다시 실었다",
      );
      const reread = await listAll(nextSession);
      expect(
        JSON.stringify(reread) === JSON.stringify(savedRows),
        "새 turn에서 30장의 첨부·SHA·순서·revision·본문이 바뀌었다",
      );
      console.log(
        JSON.stringify({
          sourceFixturePixels: 30,
          cpPreparedToFakeHermesPixels: 30,
          cpStoredMapping: 30,
          nextTurnReuse: 30,
          additionalRecords: 0,
          crosslinked: "MARKER_MISMATCH",
          modelSummary: mapping.modelSummary,
          realProvider: mapping.realProvider,
          semanticAccuracy: mapping.semanticAccuracy,
          ocrAccuracy: "UNMEASURED",
          rows: mapping.rows,
        }),
      );
      const assetId = ids[0]!;
      expectStatus(
        await call(
          context,
          `/chat/conversations/${conversationId}/media-observations/${assetId}`,
          {
            method: "PUT",
            token: ownerToken,
            body: {
              expectedRevision: 1,
              requestId: randomUUID(),
              observation: {
                status: "SUCCEEDED",
                summary: "사용자 정정",
                coverage: { mode: "ORIGINAL" },
              },
            },
          },
        ),
        200,
        "사용자 정정",
      );
      const denied = await callTool(
        context,
        token,
        RECORD,
        {
          assetId: String(assetId),
          expectedRevision: 2,
          requestId: randomUUID(),
          observation: {
            status: "SUCCEEDED",
            summary: "모델 대체",
            coverage: { mode: "ORIGINAL" },
          },
        },
        contextFor(token, RECORD, nextSession),
      );
      expect(
        denied.isError && denied.text.includes("MEDIA_OBSERVATION_CONFLICT"),
        "MODEL이 USER 정정을 덮어썼다",
      );
      const corrected = (await listAll(nextSession)).find(
        (item) => item.assetId === String(assetId),
      );
      expect(
        corrected?.revision === 2 &&
          corrected.sourceAssurance === "USER_CORRECTION" &&
          corrected.observation.summary === "사용자 정정",
        "거부 뒤 USER 정정 본문이 유지되지 않았다",
      );
      const missing = await fetch(
        `${context.api.replace(/\/api\/v1$/, "")}/mcp`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            id: 1,
            method: "tools/call",
            params: { name: LIST, arguments: {} },
          }),
        },
      );
      expect(
        (await missing.text()).includes("호출 맥락을 확인할 수 없습니다"),
        "서명 없는 관찰을 허용했다",
      );
      const child = `native-${randomUUID()}`;
      expect(
        (
          await fetch(
            `${context.api.replace(/\/api\/v1$/, "")}/internal/hermes/session-bindings/subagent`,
            {
              method: "POST",
              headers: {
                Authorization: `Bearer ${token}`,
                "Content-Type": "application/json",
              },
              body: JSON.stringify(
                signedSubagentRegistration(
                  token,
                  nextSession,
                  nextSession,
                  child,
                ),
              ),
            },
          )
        ).status === 201,
        "중지할 native 자식 등록 실패",
      );
      const executionId = await turn.executionId;
      expectStatus(
        await call(context, `/chat/executions/${executionId}/stop`, {
          method: "POST",
          token: ownerToken,
        }),
        202,
        "관찰 실행 중지",
      );
      context.hermes.releaseHeldRun();
      await turn.completed;
      const cancelled = await callTool(
        context,
        token,
        LIST,
        {},
        signedCallContext(
          token,
          LIST,
          nextSession,
          child,
          `call_${randomUUID()}`,
        ),
      );
      expect(
        cancelled.isError &&
          cancelled.text.includes("호출 맥락을 확인할 수 없습니다"),
        "중지된 native 자식이 관찰을 읽었다",
      );
      expectStatus(
        await call(context, `/admin/agent-tokens/${issued!.id}`, {
          method: "DELETE",
          token: context.tokens.dad,
        }),
        200,
        "관찰 토큰 폐기",
      );
      const revoked = await fetch(
        `${context.api.replace(/\/api\/v1$/, "")}/mcp`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
          },
          body: '{"method":"tools/list"}',
        },
      );
      expect(revoked.status === 401, "폐기 토큰을 허용했다");
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      context.hermes.releaseHeldRun();
      await completed?.catch(() => undefined);
      const cleanup: Promise<unknown>[] = [];
      if (issued !== undefined)
        cleanup.push(
          call(context, `/admin/agent-tokens/${issued.id}`, {
            method: "DELETE",
            token: context.tokens.dad,
          }).then((response) => expectStatus(response, 200, "관찰 토큰 정리")),
        );
      if (conversationId !== undefined)
        cleanup.push(
          call(context, `/chat/conversations/${conversationId}`, {
            method: "DELETE",
            token: ownerToken,
          }).then((response) => expectStatus(response, 204, "관찰 대화 정리")),
        );
      cleanup.push(
        call(context, `/admin/agents/${AGENT}`, {
          method: "PATCH",
          token: context.tokens.dad,
          body: {
            enabled: false,
            visibility: "PRIVATE",
            ownerEmail: NEW_PERSON.email,
          },
        }).then((response) =>
          expectStatus(response, 200, "관찰 에이전트 정리"),
        ),
      );
      const results = await Promise.allSettled(cleanup);
      rmSync(fixtureDir, { recursive: true, force: true });
      if (!failed) {
        const rejected = results.find((result) => result.status === "rejected");
        if (rejected?.status === "rejected") throw rejected.reason;
      }
    }
  },
};
