# 외부 읽을거리 소스 관리

읽을거리 소스의 원본은 작업 폴더의 `state/career.sqlite` 다.
소스 키는 주제가 아니라 발행처를 식별한다.

`help`, `--help`, `-h`와 `template`는 저장소를 열지 않는다.
`template`는 저장할 본문의 초안을 출력하고 저장하지 않는다.

```bash
<CAREER_LOCAL> study-sources template \
  --key example-engineering \
  --title "Example Engineering" \
  --category techBlog \
  --url "https://example.com/engineering" \
  --adapter page \
  --note "새 기술 블로그를 수집한다"
```

출력 JSON은 API 경로에 쓰는 `sourceKey`와 요청 본문 `payload`를 나눈다.
`payload`에는 `title`, `category`, `adapter`, `url`, `feedUrl`, `enabled`, `note`, `expectedVersion`이 들어간다.

좋은 키:

- `daangn-tech`
- `naver-d2`
- `geeknews`

피해야 할 키:

- `daangn-backend-event-driven`
- `naver-search-architecture-recent`

## 카테고리

- `techBlog`
  - 회사나 프로젝트가 직접 운영하는 기술 발행 채널이다.
- `geek`
  - 뉴스, 큐레이션, 릴리스 동향 채널이다.
- `video`
  - 공식 YouTube 채널처럼 공개 영상 피드를 제공하는 발행 채널이다.
- `ai`
  - 프론티어 모델 기업이 직접 발행하는 문서, 연구, 기술 발표 채널이다.
  - 모델, 에이전트, 하네스, 평가, 안전성, 추론과 개발 도구를 함께 수집한다.

## 조회

```bash
<CAREER_LOCAL> study-sources list
```

필요하면 응답에서 카테고리와 활성 상태를 확인한다.
소스 목록을 수정하기 전에 현재 version을 다시 읽는다.

## 소스 추가

다음 명령은 새 소스를 저장소에 추가한다.
`--note`에는 추가 이유를 남긴다.

```bash
<CAREER_LOCAL> study-sources add \
  --key example-engineering \
  --title "Example Engineering" \
  --category techBlog \
  --url "https://example.com/engineering" \
  --feed-url "https://example.com/engineering/feed.xml" \
  --adapter feed \
  --note "새 기술 블로그를 수집한다"
```

URL과 피드 URL은 HTTPS만 허용한다.
YouTube 채널은 채널 ID 기반 공식 Atom 피드를 `feedUrl`로 등록한다.
채널 검색 결과의 표시 이름만 믿지 않고 채널 URL, channel ID와 Atom feed의 `<title>`이 같은 채널인지 확인한다.
`youtube` 어댑터는 Atom feed를 먼저 사용하고 feed가 없거나 비었을 때만 공개 채널 페이지를 보조 경로로 사용한다.

`feed` 어댑터에는 `feedUrl`이 필요하다.
`page` 어댑터에는 `url`이 필요하다.
`youtube` 어댑터에는 채널 `url`이 필요하다.

## 소스 변경과 중지

소스의 제목, 카테고리, 어댑터와 URL은 `update`로 바꾼다.
기존 값을 지울 때는 `--clear-url` 또는 `--clear-feed-url`을 사용한다.

```bash
<CAREER_LOCAL> study-sources update \
  --key example-engineering \
  --title "Example Engineering Blog" \
  --note "발행처 이름을 실제 이름으로 고친다"
```

수집에 실패한 소스를 끌 때는 실패 원인을 `--note`에 남긴다.

```bash
<CAREER_LOCAL> study-sources disable \
  --key example-engineering \
  --note "RSS 응답이 계속 실패해 수집을 중지한다"
```

복구를 확인한 소스는 같은 방식으로 `enable` 한다.
소스가 다른 사람에 의해 바뀌어 `409`가 나면 목록을 다시 읽고 변경 내용을 검토한 뒤 명령을 다시 실행한다.
