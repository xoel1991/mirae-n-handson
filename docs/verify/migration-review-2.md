# 검증 결과 (2차) — 문항 검색(search.php) 이관

- 검증 대상: `upstream/main...HEAD` (브랜치 `day1`, 커밋 4개: `a9632f9` · `176b2b5` · `d577f8e` · `97d32d5`). `git fetch upstream` 후 검증
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관
- 기준 문서: `templates/approval-checklist.md` (v0)
- 검증 일자: 2026-10-06 · 코드는 수정하지 않았다.
- 참고: 이 리뷰는 1차 리뷰 반영(`97d32d5`의 `bin/` 추적 해제, 작업 트리의 `.gitignore`)을 진행한 세션에서 실행했다. 만든 쪽과 검증하는 쪽이 완전히 분리되지 않았다.

## 판정: 반려

이관 코드 · 테스트 · 동작 보존은 1차와 같이 통과했고, 1차 이슈 1(빌드 산출물 54개)은 해소됐다. 남은 반려 사유는 한 줄이다: **준비 커밋 `176b2b5` 의 문서 · 설정(7개 파일)이 요청 문장 "엔드포인트 1개 이관"으로 설명되지 않는다**(1차 이슈 2, 변경 없음). 이 파일들을 범위에 넣을지는 사람이 정할 일이며, 범위에 넣기로 하면 승인 조건을 모두 충족한다.

## 기준별 결과

| 기준 | 결과 | 근거 |
|---|---|---|
| 1. 테스트 통과 | 충족 | 아래 "실행한 테스트". 실패 · 오류 0건. 기존 테스트 삭제 · `@Disabled` · `skip` 추가 없음 |
| 2. 치명 이슈 0건 | 충족 | 비밀값 리터럴 없음, SQL 이어 붙이기 없음(JPA Criteria 바인딩), 삭제 · 덮어쓰기 코드 없음 |
| 3. 요청 범위 이탈 없음 | **미충족** | 이슈 1. 그 밖의 파일(이관 코드 · 테스트 · `docs/item-bank/*` · characterization 베이스라인)은 범위 안 |
| 4. 컨벤션 준수 | 충족 | 아래 "컨벤션 점검 요약". 위반 0건 |

## 실행한 테스트

| 명령 | 결과 |
|---|---|
| `cd modern/api && ./gradlew cleanTest test` | 통과 — 48건, 실패 0 · 오류 0 · 건너뜀 0 |
| `cd characterization && npm test` (레거시 대상) | 통과 — 파일 3개, 32건 통과 |
| `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` (새 API) | 통과 — 31건 통과 · 1건 건너뜀 · 실패 0 |

- 새 API(8080)는 이미 떠 있었다(`GET /api/units` → 200). 기동하지 않았다.
- 건너뛴 1건은 1차와 같은 `characterization/tests/example-units.test.js` 의 `skipIf` 다. 이번 diff 에 없는 파일이다.
- characterization 은 테스트 · 스냅샷 모두 **추가**(`A`)만 있고 기존 스냅샷 수정은 없다. 테스트 코드에 `localhost:808x` 직접 기재 없음.
- `modern/web` 은 변경이 없어 실행하지 않았다.

## 1차 대비

| 1차 이슈 | 2차 상태 |
|---|---|
| 1. 빌드 산출물 54개 커밋 (경고→반려) | **해소.** `97d32d5` 에서 추적 해제, `git ls-files modern/api/bin` = 0. 단, `.gitignore` 는 미커밋(이슈 2) |
| 2. 이관과 무관한 문서 · 설정 (경고→반려) | **남음.** 이슈 1 |
| 3. `ItemSearchCondition` 웹 타입 의존 (경고) | 제안으로 낮춤(이슈 3). `ItemSearchConditionTest.java:15-16` 이 `LinkedMultiValueMap` 으로 단위 테스트하고 있어 "테스트하기 어렵다"는 근거가 성립하지 않는다 |
| 4. 의심 동작 보고 확인 (제안) | 남음(이슈 4) |

치명 0 → 0, 경고 3 → 2(그중 1건은 1차 반영의 마무리). 새로 생긴 코드 이슈는 없다.

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 (반려 사유) | `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `docs/assignment/ARCHITECTURE.md:1`, `docs/assignment/BUSINESS-RULES.md:1`, `docs/assignment/ERD.md:1` | 모두 준비 커밋 `176b2b5` 에서 새로 추가된 파일이다. 과제 배포 모듈 문서와 작업 환경 설정은 "item-bank 엔드포인트 1개 이관"으로 설명되지 않는다(체크리스트 3번 두 번째 항목) | (a) 준비 커밋을 별도 PR 로 분리하거나, (b) 사람이 "1회차 결과물 전체"를 이 브랜치의 요청 범위로 확정한다. (b)면 이 이슈는 닫힌다 |
| 2 | 경고 | `.gitignore:13` (작업 트리, 미커밋) | `bin/` 추가가 커밋되지 않아 이 diff 에 없다. 이대로 머지하면 IDE 가 다시 만든 `modern/api/bin/` 이 `git add modern` 한 번에 재추적된다 | `.gitignore` 를 커밋한다(사람이 이미 수정을 승인함) |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:10,41` | `MultiValueMap` 을 받아 조건을 만든다. 호출처는 `ItemController.java:36` 한 곳이고 단위 테스트도 있어 현재 문제는 없다 | 다음 변경에서 파라미터 해석을 별도 파서로 분리할지 검토 |
| 4 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchSpecifications.java:17,45`, `docs/item-bank/BUSINESS-RULES.md:125` | 의심 동작(난이도 미지정 시 5 제외, `%` · `_` 미이스케이프)이 코드 주석에만 있고 `docs/item-bank/` 에 "의심 동작" 목록이 없다(CLAUDE.md §5) | `docs/item-bank/` 에 의심 동작 목록을 정리 |
| 5 | 제안 | `modern/api/src/main/java/com/example/item/ItemRepository.java:16`, `ItemSearchRow.java:17` | 태그를 지연 로딩해 페이지당 최대 20번 추가 조회가 생긴다. 트랜잭션 안이라 동작은 맞고 주석에도 의도가 적혀 있다 | 조회량이 문제가 되면 `@BatchSize` 등 일괄 로딩 검토(설정 변경이 필요하면 사람 승인 후) |

## 확인 필요

- `docs/verify/migration-review-1.md` 가 이 diff 에 들어 있다(`97d32d5`). 리뷰 기록을 이관 PR 에 함께 넣을지는 팀 규칙이 없다. 실습 가이드는 커밋하라고 하므로 이번 판정에서는 범위 이탈로 세지 않았다.

## 컨벤션 점검 요약

| 항목 | 결과 |
|---|---|
| 컨트롤러는 서비스만 주입, Repository · SQL 없음 | 준수 (`ItemController.java:35-37`) |
| 응답은 record DTO | 준수 (`ItemSearchResponse`, `ItemSearchRow`) |
| 조회 메서드 `@Transactional(readOnly = true)` | 준수 (클래스 레벨, `ItemService.java:15`) |
| 빈 catch · `System.out` · `printStackTrace` · 와일드카드 import | 추가된 줄에 없음 |
| 로그에 학생 식별자 · 이메일 · 토큰 | 없음 (`ItemService.java:54-55` 는 건수 · 페이지만) |
| 숫자 리터럴 상수화 | 준수 (`PAGE_SIZE`, `MAX_PAGE`, `KEYWORD_MAX_LENGTH`, `TAG_MAX_LENGTH`, `DEFAULT_LEVEL_CEILING`) |
| 한 줄 120자 이내 | 준수 (변경된 Java 파일 전부, 글자 수 기준) |
| 새 public 서비스 메서드 · 엔드포인트 테스트 | 있음 (`ItemServiceTest.java:96-126` 2건, `ItemControllerTest.java:85-90` 외) |
| 테스트 `@DisplayName` | 변경된 테스트 4개 파일 모두 `@Test` 수 = `@DisplayName` 수 |
| 의존성 · 스키마 · `application.yml` 변경 | 없음 |

## 다음 단계

1. `.gitignore` 를 커밋한다(이슈 2).
2. 이슈 1 의 범위를 사람이 정한다: 준비 커밋 분리 또는 "1회차 결과물 전체"로 요청 범위 확정.
3. (2)에서 범위를 확정하면 승인 기준 네 개가 모두 충족된다. 분리하면 분리 후 다시 `/verify` 를 실행한다.
