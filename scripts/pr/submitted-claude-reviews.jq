# 이번 실행에서 현재 커밋에 제출한 봇 리뷰만 센다.
[.[][] | select(
  .user.login == "claude[bot]"
  and .commit_id == $sha
  and .id > $last
  and .submitted_at != null
  and (.state == "COMMENTED" or .state == "APPROVED" or .state == "CHANGES_REQUESTED")
  and ((.body // "") | test("\\S"))
)] | length
