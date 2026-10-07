import { expect, test } from "bun:test";
import { normalizePlaceAddress } from "../src/editor/components.ts";

test.each([
  ["대한민국 서울특별시 가상구 예시로 1", "서울 가상구 예시로 1"],
  ["서울시  가상구 예시로 1", "서울 가상구 예시로 1"],
  ["부산광역시 가상구 예시로 2", "부산 가상구 예시로 2"],
  ["세종특별자치시 예시로 3", "세종 예시로 3"],
  ["경기도 가상시 예시로 4", "경기 가상시 예시로 4"],
  ["강원특별자치도 가상군 예시면 5", "강원 가상군 예시면 5"],
  ["강원도 가상군 예시면 5", "강원 가상군 예시면 5"],
  ["전라북도 가상시 예시로 6", "전북 가상시 예시로 6"],
  ["전북특별자치도 가상시 예시로 6", "전북 가상시 예시로 6"],
  ["충청남도 가상시 예시로 7", "충남 가상시 예시로 7"],
  ["제주특별자치도 가상시 예시로 8", "제주 가상시 예시로 8"],
])("%s 는 %s 와 같은 표기가 된다", (address, expected) => {
  expect(normalizePlaceAddress(address)).toBe(expected);
});

test("정식 이름과 줄임 표기로 쓴 같은 주소는 같은 값이 된다", () => {
  expect(normalizePlaceAddress("대한민국 경상북도 가상시 예시로 9")).toBe(
    normalizePlaceAddress("경북 가상시 예시로 9"),
  );
});

test("시도 이름이 아닌 첫 낱말과 둘째 낱말 뒤의 시도 이름은 바꾸지 않는다", () => {
  expect(normalizePlaceAddress("가상시 서울특별시로 10")).toBe("가상시 서울특별시로 10");
  expect(normalizePlaceAddress("가상구 경기도 11")).toBe("가상구 경기도 11");
});
