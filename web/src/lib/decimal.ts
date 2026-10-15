/**
 * 십진 글을 부동소수점 없이 비교하고 곱한다. Control Plane 은 금액을 `BigDecimal` 로 판정하므로 화면도 같은 값으로
 * 견줘야 상한과 딱 같은 주문을 넘는다고 잘못 보이지 않는다.
 */
export type Decimal = { units: bigint; scale: number };

/** `12.34` 같은 0 이상의 십진 글을 읽는다. 모양이 틀리면 null 이다. */
export function parseDecimal(text: string): Decimal | null {
  const match = /^([0-9]+)(?:\.([0-9]+))?$/.exec(text);
  if (!match) return null;
  const fraction = match[2] ?? "";
  return { units: BigInt(match[1] + fraction), scale: fraction.length };
}

export function multiplyDecimal(a: Decimal, b: Decimal): Decimal {
  return { units: a.units * b.units, scale: a.scale + b.scale };
}

/** a 가 b 보다 크면 양수, 같으면 0, 작으면 음수다. */
export function compareDecimal(a: Decimal, b: Decimal): number {
  const scale = Math.max(a.scale, b.scale);
  const left = a.units * 10n ** BigInt(scale - a.scale);
  const right = b.units * 10n ** BigInt(scale - b.scale);
  return left === right ? 0 : left > right ? 1 : -1;
}

/** 두 십진 글에서 a 가 b 보다 큰가. 어느 쪽이든 읽지 못하면 거짓이다. */
export function decimalExceeds(a: string, b: string): boolean {
  const left = parseDecimal(a);
  const right = parseDecimal(b);
  return left !== null && right !== null && compareDecimal(left, right) > 0;
}
