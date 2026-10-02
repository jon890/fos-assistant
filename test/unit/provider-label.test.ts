import assert from "node:assert/strict";
import test from "node:test";
import {
  knownProviderLabel,
  providerLabel,
} from "../../web/src/lib/provider-label.ts";

test("아는 provider id 는 표시 이름으로 바뀐다", () => {
  assert.equal(providerLabel("openai-codex"), "ChatGPT 구독");
  assert.equal(providerLabel("anthropic"), "Anthropic");
  assert.equal(providerLabel("gemini"), "Google");
  assert.equal(providerLabel(" openai "), "OpenAI");
});

test("모르는 provider id 는 원문 대신 다른 제공사로 그린다", () => {
  assert.equal(providerLabel("some-new-provider"), "다른 제공사");
  assert.equal(providerLabel("constructor"), "다른 제공사");
});

test("비어 있는 값은 null 이다", () => {
  assert.equal(providerLabel(""), null);
  assert.equal(providerLabel("   "), null);
  assert.equal(providerLabel(null), null);
  assert.equal(providerLabel(undefined), null);
});

test("knownProviderLabel 은 표에 있는 id 만 표시 이름으로 바꾼다", () => {
  assert.equal(knownProviderLabel("openai-codex"), "ChatGPT 구독");
  assert.equal(knownProviderLabel(" openai "), "OpenAI");
});

test("knownProviderLabel 은 모르는 id 와 빈 값에 null 을 준다", () => {
  assert.equal(knownProviderLabel("some-new-provider"), null);
  assert.equal(knownProviderLabel("constructor"), null);
  assert.equal(knownProviderLabel(""), null);
  assert.equal(knownProviderLabel("   "), null);
  assert.equal(knownProviderLabel(null), null);
  assert.equal(knownProviderLabel(undefined), null);
});
