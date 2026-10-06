# 외주사 PR 판정표

기준 문서: `templates/approval-checklist.md` (v0) · 검증 절차: `/verify` (`.claude/skills/verify/SKILL.md`)

| PR | 판정 | 미충족 기준 | 근거(파일:줄번호) | 외주사에 돌려보낼 수정 요청 |
|---|---|---|---|---|
| pr-1-missing-tests | 반려 | 테스트 통과(테스트 충분성) · 컨벤션 준수 | `modern/api/src/main/java/com/example/item/UnitService.java:36` 새 public 서비스 메서드, `modern/api/src/main/java/com/example/item/UnitController.java:26-27` 새 엔드포인트 — 둘 다 테스트 0건(CLAUDE.md §2) | `countActiveItemsByUnit` 의 Mockito 테스트와 `GET /api/units/item-counts` 의 `@WebMvcTest` 를 각 1건 이상 추가해 다시 제출 |
| pr-2-hardcoded-secret | 반려 | 치명 이슈 0건 · 컨벤션 준수 | [치명] `modern/api/src/main/java/com/example/assignment/GradingServerClient.java:34` 비밀번호 리터럴(`:33` 계정명도 리터럴). [경고] `:61` JSON 문자열 이어 붙이기로 `studentId` 미이스케이프. [경고] `:47` 매직넘버 `200` | 계정 · 비밀번호를 설정(환경변수) 주입으로 바꾸고 노출된 비밀번호는 폐기 · 교체. 요청 본문은 `ObjectMapper` 로 직렬화. `200` 은 상수로 |
| pr-3-out-of-scope | 반려 | 요청 범위 이탈 없음 · 컨벤션 준수 | 요청과 무관한 `modern/api/src/main/java/com/example/assignment/DistributionService.java:52,62`, `modern/api/src/main/java/com/example/assignment/ReportService.java:65,71,83` "정리"(`:71` 은 로그 출력 변경). [경고] 새 엔드포인트 `modern/api/src/main/java/com/example/item/UnitController.java:31-32` 에 `@WebMvcTest` 없음 | assignment 2개 파일 변경을 이 PR 에서 빼고(필요하면 별도 요청 · PR), `GET /api/units/{code}/items?level=` 의 `@WebMvcTest` 를 추가 |

## 참고 — 요청 문장과 테스트 결과

| PR | 요청 문장(patch 맨 위) | `./gradlew cleanTest test` |
|---|---|---|
| pr-1-missing-tests | 단원별 문항 수 통계 API 추가 | 48건 통과 · 실패 0 |
| pr-2-hardcoded-secret | 외부 채점 서버 연동 클라이언트 추가 | 49건 통과 · 실패 0 |
| pr-3-out-of-scope | 문항 검색에 난이도 필터 추가 | 50건 통과 · 실패 0 |

- 세 건 모두 테스트는 통과했다. 반려 사유는 각각 테스트 부족 · 비밀값 · 범위 이탈이다.
- 제안(판정 무관): pr-1 `UnitService.java:39` 단원마다 count 쿼리 1회(N+1) — 단원이 많아지면 GROUP BY 로.
- characterization 은 세 건 모두 이관 코드가 아니라 실행하지 않았다.
