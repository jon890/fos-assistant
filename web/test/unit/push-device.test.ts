import assert from "node:assert/strict";
import test from "node:test";
import {
  base64UrlToBytes,
  deviceName,
  iosInfo,
  pushScreenState,
  sameKey,
  type PushScreenInput,
} from "../../src/lib/push-device.ts";

// 「알림」 화면의 기기 이름과 상태 판정을 본다(docs/features/attention/spec.md 의 REQ1-REQ3).

const UA = {
  iphone16:
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.4 Mobile/15E148 Safari/604.1",
  iphone163:
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.3 Mobile/15E148 Safari/604.1",
  iphoneChrome:
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/120.0.6099.119 Mobile/15E148 Safari/604.1",
  ipadDesktop:
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
  macChrome:
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
  macSafari:
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
  windowsEdge:
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0",
  windowsFirefox:
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
  androidChrome:
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
  unknown: "curl/8.0",
};

test("기기 이름은 OS 와 브라우저로 만들고 홈 화면 앱은 「앱」 이다", () => {
  const cases: [string, boolean, number, string][] = [
    [UA.iphone16, true, 5, "iPhone 앱"],
    [UA.iphone16, false, 5, "iPhone Safari"],
    [UA.iphoneChrome, false, 5, "iPhone Chrome"],
    [UA.ipadDesktop, false, 5, "iPad Safari"],
    [UA.macChrome, false, 0, "Mac Chrome"],
    [UA.macSafari, false, 0, "Mac Safari"],
    [UA.windowsEdge, false, 0, "Windows Edge"],
    [UA.windowsFirefox, false, 0, "Windows Firefox"],
    [UA.androidChrome, true, 5, "Android 앱"],
    [UA.unknown, false, 0, "브라우저"],
  ];
  for (const [ua, standalone, touch, expected] of cases)
    assert.equal(deviceName(ua, standalone, touch), expected, ua);
});

test("iOS 와 그 버전을 읽고 Mac 모양의 iPad 도 iOS 로 본다", () => {
  assert.deepEqual(iosInfo(UA.iphone16, 5), { ios: true, version: [16, 4] });
  assert.deepEqual(iosInfo(UA.iphone163, 5), { ios: true, version: [16, 3] });
  assert.deepEqual(iosInfo(UA.ipadDesktop, 5), { ios: true, version: [17, 1] });
  assert.deepEqual(iosInfo(UA.macSafari, 0), { ios: false, version: null });
  assert.deepEqual(iosInfo(UA.windowsEdge, 0), { ios: false, version: null });
});

const BASE: PushScreenInput = {
  serverEnabled: true,
  supported: true,
  permission: "default",
  ios: { ios: false, version: null },
  standalone: false,
  registered: false,
};

test("화면 상태는 서버, iOS 버전, iOS 탭, 지원, 허락, 등록 차례로 고른다", () => {
  const ios = (version: [number, number]) => ({ ios: true, version });
  const cases: [Partial<PushScreenInput>, string][] = [
    [{}, "ready"],
    [{ serverEnabled: false, permission: "denied" }, "server-off"],
    [{ ios: ios([16, 3]), standalone: true }, "ios-old"],
    [{ ios: ios([16, 3]), standalone: false }, "ios-old"],
    [{ ios: ios([16, 4]), standalone: false, supported: false }, "ios-install"],
    [{ ios: ios([17, 2]), standalone: true }, "ready"],
    [{ ios: { ios: true, version: null }, standalone: true }, "ready"],
    [{ supported: false }, "unsupported"],
    [{ permission: "unsupported" }, "unsupported"],
    [{ permission: "denied", registered: true }, "denied"],
    [{ permission: "granted", registered: true }, "registered"],
    [{ permission: "granted", registered: false }, "ready"],
  ];
  for (const [input, expected] of cases)
    assert.equal(pushScreenState({ ...BASE, ...input }), expected, JSON.stringify(input));
});

test("공개 키를 바이트로 바꾸고 기존 구독의 키와 비교한다", () => {
  const key = base64UrlToBytes("AQID_-8");
  assert.deepEqual(Array.from(key), [1, 2, 3, 255, 239]);
  assert.equal(sameKey(new Uint8Array([1, 2, 3, 255, 239]).buffer, key), true);
  assert.equal(sameKey(new Uint8Array([1, 2, 3]).buffer, key), false);
  assert.equal(sameKey(null, key), false);
});
