# 검증 결과 (3차) — 문항 검색(search.php) 이관

- 검증 대상: `upstream/main...HEAD -- modern characterization` (브랜치 `day1`)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관
  - 대상: `legacy/item-bank-php/search.php` → `GET /api/items/search`
- 기준 문서: `templates/approval-checklist.md` (v1 · 2회차)
- 검증 일자: 2026-10-06 · 소스 코드는 수정하지 않았다.
- 검증 방식: `/verify` 절차대로 체크리스트 대조는 `reviewer`, 테스트 실행은 `tester` Sub-agent 에 위임했고, 메인 세션이 두 보고를 합쳐 최종 판정했다.

## 판정: 승인

경로를 `modern` · `characterization` 으로 한정한 범위에서 네 기준의 판정 항목이 모두 "예"다. 치명 · 경고 이슈는 0건이고 제안은 2건이다. reviewer 와 tester 의 보고는 엇갈리지 않았다.

## 기준별 결과

| 기준 | 결과 | 근거 |
|---|---|---|
| 1. 테스트 통과 | 충족 | 아래 "실행한 테스트"의 실패 · 오류가 0건이다. 기존 테스트 삭제와 `skip` · `@Disabled` 추가가 없다(reviewer). characterization 테스트 · 스냅샷은 베이스라인 커밋 `a9632f9` 에서 추가만 됐고, 이관 커밋은 건드리지 않았다. |
| 2. 치명 이슈 0건 | 충족 | 비밀값 리터럴이 없다. 조회만 하고 삭제 · 덮어쓰기 코드는 없다(reviewer). |
| 3. 요청 범위 이탈 없음 | 충족 (경로 한정) | 변경 파일 14개(+1817/-1)가 search 이관, 그 테스트, characterization 베이스라인으로 설명된다. `build.gradle` · `src/main/resources` · `db/` · `legacy/` 변경은 0건이다. |
| 4. 컨벤션 준수 | 충족 | 컨트롤러는 서비스만 쓰고 record `ItemSearchResponse` 를 반환한다. 빈 catch, 표준 출력, 로그의 개인정보가 모두 0건이다(`ItemService.java:54-55` 는 건수와 페이지만 남긴다). |

## 실행한 테스트

| 명령 | 실행 주체 | 결과 |
|---|---|---|
| `cd modern/api && ./gradlew cleanTest test` | 메인 세션 | 48건 통과, 실패 0 · 오류 0 · 건너뜀 0 |
| `cd characterization && npm test` (레거시 대상) | tester | 32건 통과, 실패 0 |
| `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` (새 API 대상) | tester | 31건 통과, 1건 건너뜀, 실패 0 |

- tester 가 실행한 `./gradlew test` 는 UP-TO-DATE(캐시)로 끝나 이번 검증의 실행 결과로 쓸 수 없었다. `cleanTest` 가 tester 의 허용 명령에 없어서, 이 명령만 메인 세션이 직접 실행했다.
- 새 API 대상에서 건너뛴 1건은 `characterization/tests/example-units.test.js:21` 의 `it.skipIf(legacyOnly)` 다. 레거시에서만 도는 예시 테스트이고, 이번 diff 에는 들어 있지 않다.
- `modern/web` 은 변경이 없어 실행하지 않았다.
- tester 가 추가한 테스트: 없음. 새 public 메서드 · 엔드포인트에 이미 테스트가 있다.
  - `ItemControllerTest` 3건
  - `ItemServiceTest` 2건
  - `ItemSearchConditionTest` 12건
  - `ItemRepositoryTest` 7건
- 실행 후 `git status --short` 결과 `modern/` · `characterization/` 아래 변경은 0건이다.

## 이슈 목록

치명 0건 · 경고 0건 · 제안 2건

| 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|
| 제안 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:67-77` | PHP `$_GET` 은 같은 키를 나온 순서대로 덮어쓴다(`search.php:54-80`). 그래서 `q[]=a&q=b` 이면 PHP 는 `b` 를 쓰고, 새 코드는 `a` 를 쓴다. `q[0]=a` 같은 인덱스 배열 형식은 새 코드가 무시한다. characterization 케이스에는 이런 입력이 없다. | 의도한 차이로 확정해 의심 동작 목록에 남기거나, 원본 query string 기준으로 마지막 형식을 고르게 바꾼다. 사람이 정한다. |
| 제안 | `modern/api/src/main/java/com/example/item/ItemService.java:49-57` | 검색 중 예외가 나면 레거시는 HTTP 200 에 오류 문구를 돌려주고(`search.php:726-729`), 새 API 는 `GlobalExceptionHandler` 를 거쳐 500 을 돌려준다. | CLAUDE.md §2 의 500 규칙에 따른 의도된 차이로 기록한다. |

## 확인 필요

1. **경로 밖 변경.** 같은 범위에 `CLAUDE.md`, `.mcp.json`, `dbhub.toml`, `mcp-skeleton/`, `.claude/skills/**`, `docs/**`, `templates/*` 변경 23개가 있다. 이번 리뷰는 경로를 한정해 보지 않았다. 같은 PR 로 머지한다면 기준 3과 비밀값 여부를 다시 판정해야 한다.
2. **스냅샷 출처.** characterization 베이스라인이 같은 범위에 들어 있다. 레거시 대상의 `npm run baseline -- item-bank` 로 만든 스냅샷인지 사람이 확인한다.
3. **경고 문구 미이관.** 레거시 화면의 입력 경고 문구(`search.php:88`, `92`, `95`, `113`, `116`, `142`, `225`, `238`, `242`, `259`, `270`, `323`, `340`, `347`)가 응답에 없다. 정규화 모양 `{status, rows, count, message}` 밖이라 테스트에 걸리지 않는다. 화면에 필요한지 사람이 정한다.
4. **정규화 비대칭.** `characterization/lib/normalize.mjs:83` 은 HTML 셀의 공백만 정리하고 JSON 은 원문을 쓴다. 지금 시드에서는 영향이 없다. 이관 코드의 문제는 아니다.
5. **`.gitignore` 미커밋.** 작업 트리에 `bin/` 추가만 남아 있다.

## 의심 동작 목록 (레거시 그대로 옮김, 고치지 않음)

1. 난이도를 비우면 `level < 5` 라서 난이도 5 가 빠진다(`search.php:213-216`).
2. 키워드의 `%` · `_` 를 이스케이프하지 않아 와일드카드로 동작한다(`search.php:94-98`).
3. 1~5 밖의 난이도는 PHP `(int)` 로 바꿔 비교한다. 예를 들어 `05` 는 5, `1e1` 은 10, `abc` 는 0 이 된다(`search.php:223-229`).
4. 단원 코드의 형식 오류나 소문자는 경고만 하고 그대로 조회한다. 정렬 규칙 때문에 `m5-1` 도 `M5-1` 과 일치한다(`search.php:111-120`).
5. 태그에 `%` · `_` 가 있으면 경고만 하고 정확히 일치로 조회한다(`search.php:237-239`).
6. 페이지가 전체보다 뒤면 행은 0건이고 건수는 그대로다(`search.php:349`, `523`, `526`).
7. **(새 API 와 다름)** 검색 중 DB 오류의 응답 코드가 다르다(이슈 목록 2번째).
8. **(새 API 와 다름)** 배열 키와 단일 키가 섞이면 고르는 값이 다르다(이슈 목록 1번째).

## 1차 대비

`migration-review-1.md` 는 반려였고 이슈 3건이 있었다.
- 빌드 산출물 54개 커밋: `modern/api/bin/**` 가 `97d32d5` 에서 삭제돼 순변경 0 이다.
- 무관한 문서 · 설정 섞임: 경로 한정으로 범위에서 뺐다. 위 "확인 필요" 1번에 남아 있다.
- `ItemSearchCondition` 의 웹 타입 의존(경고): 이번 리뷰에서는 다시 지적되지 않았다.

이번 결과는 치명 · 경고 0건, 제안 2건이다.
