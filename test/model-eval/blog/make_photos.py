#!/usr/bin/env python3
"""블로그 사진 평가가 쓰는 합성 사진 12장을 만든다.

쓰는 법:
    python3 test/model-eval/blog/make_photos.py --font <한글 글꼴 파일> --out test/model-eval/blog/photos
    (.ttc 컬렉션이면 굵은 굵기의 --font-index 를 고른다)

`photos/photos.json` 의 answer 를 사진마다 크게 그린다. 모델이 그 사진을 읽었는지 글자로 가리기 위해서다.

글꼴은 인자로 받는다. Pillow 와 한글 글꼴은 이 저장소의 의존이 아니라 개발 머신에서 한 번 돌리는 도구의 의존이다.
만든 JPEG 는 저장소에 커밋한다. 평가가 도는 머신의 글꼴에 기대지 않고 매번 같은 바이트로 평가하려는 것이다.
무작위 값을 쓰지 않으므로 같은 글꼴로 다시 돌리면 같은 바이트가 나온다.
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 1280, 960
FONT_SIZE = 110

# 사진 번호(0부터)로 고르는 배경색과 판 색. 사진마다 달라 보이게만 하면 된다.
BACKGROUNDS = [
    (196, 214, 198), (222, 205, 180), (188, 204, 222), (230, 214, 214),
    (176, 206, 186), (216, 216, 200), (204, 190, 214), (60, 70, 66),
    (190, 200, 210), (224, 206, 188), (200, 214, 226), (226, 220, 196),
]
PANELS = [
    (250, 244, 232), (246, 238, 214), (255, 255, 255), (238, 240, 232),
    (244, 248, 240), (252, 252, 250), (250, 240, 228), (40, 46, 44),
    (252, 250, 244), (240, 226, 200), (248, 250, 252), (255, 250, 236),
]


def draw_scene(draw, index):
    """장면을 흉내 낸 단순한 도형을 그리고 글자를 얹을 판의 사각형을 돌려준다."""
    panel = (90, 230, WIDTH - 90, HEIGHT - 230)
    if index in (0, 6):  # 간판: 위에 걸린 판과 기둥
        draw.rectangle((140, 120, 170, HEIGHT), fill=(110, 90, 70))
        draw.rectangle((WIDTH - 170, 120, WIDTH - 140, HEIGHT), fill=(110, 90, 70))
    elif index in (4, 8, 10):  # 표지판: 가운데 기둥
        draw.rectangle((WIDTH // 2 - 30, panel[3], WIDTH // 2 + 30, HEIGHT), fill=(120, 120, 120))
    elif index == 5:  # 영수증: 좁고 긴 종이
        panel = (240, 120, WIDTH - 240, HEIGHT - 120)
    elif index == 2:  # 출입문: 문틀
        draw.rectangle((40, 40, WIDTH - 40, HEIGHT - 40), outline=(90, 70, 50), width=24)
    draw.rectangle(panel, fill=PANELS[index], outline=(70, 70, 70), width=8)
    return panel


def make(photo, index, font, out_dir):
    image = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUNDS[index])
    draw = ImageDraw.Draw(image)
    panel = draw_scene(draw, index)
    color = (245, 245, 240) if index == 7 else (25, 25, 25)
    # 판 너비에 들어가지 않으면 두 줄로 나눈다.
    text = photo["answer"]
    avail = panel[2] - panel[0] - 60
    if draw.textlength(text, font=font) > avail and " " in text:
        head, _, rest = text.partition(" ")
        text = head + "\n" + rest
    cx, cy = (panel[0] + panel[2]) // 2, (panel[1] + panel[3]) // 2
    draw.multiline_text((cx, cy), text, font=font, fill=color, anchor="mm", align="center", spacing=24)
    image.save(out_dir / photo["file"], "JPEG", quality=90)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--font", required=True, help="한글 글꼴 파일")
    parser.add_argument("--font-index", type=int, default=0, help=".ttc 컬렉션에서 고를 글꼴 번호")
    parser.add_argument("--out", required=True, help="사진을 쓸 디렉터리")
    args = parser.parse_args()

    meta = json.loads((Path(__file__).parent / "photos" / "photos.json").read_text(encoding="utf-8"))
    font = ImageFont.truetype(args.font, FONT_SIZE, index=args.font_index)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    for index, photo in enumerate(meta["photos"]):
        make(photo, index, font, out_dir)


if __name__ == "__main__":
    main()
