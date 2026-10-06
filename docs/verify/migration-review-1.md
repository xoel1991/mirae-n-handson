# 검증 결과 — 문항 검색(search.php) 이관

- 검증 대상: `upstream/main...HEAD` (브랜치 `day1`, 커밋 3개: `a9632f9` · `176b2b5` · `d577f8e`)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관
- 기준 문서: `templates/approval-checklist.md` (v0)
- 검증 일자: 2026-10-06 · 코드는 수정하지 않았다.

## 판정: 반려

이관 코드 자체의 품질과 동작 보존은 통과했다. 반려 사유는 **요청 범위 이탈**이다. 빌드 산출물 54개와 이관과 무관한 문서가 같은 diff 에 섞여 있다.

## 기준별 결과

| 기준 | 결과 | 근거 |
|---|---|---|
| 1. 테스트 통과 | 충족 | 아래 "실행한 테스트" 참고. 실패 · 오류 · 건너뜀 0건 |
| 2. 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 · SQL 이어 붙이기 · 데이터 삭제 코드 없음 |
| 3. 요청 범위 이탈 없음 | **미충족** | 요청 문장으로 설명되지 않는 파일이 있다(이슈 1 · 2) |
| 4. 컨벤션 준수 | 충족(경고 1건) | 계층 · 예외 · 로깅 · 테스트 규칙 준수. 이슈 3 은 경고 |

## 실행한 테스트

| 명령 | 결과 |
|---|---|
| `cd modern/api && ./gradlew cleanTest test` | 통과 — 48건 중 실패 0 · 오류 0 · 건너뜀 0 (캐시 없이 다시 실행) |
| `cd characterization && npm test` (레거시 8081) | 통과 — 파일 3개, 32건 통과 · 건너뜀 0 |
| `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` (새 API) | 통과 — 31건 통과 · 1건 건너뜀 |

- 새 API(8080)는 이미 떠 있었고 `/api/items/search` 가 응답했다. 기동하지 않았다.
- 건너뛴 1건은 `characterization/tests/example-units.test.js:21` 의 `it.skipIf(legacyOnly)` 이다. 이 파일은 이번 diff 에 없다. 새 API 에 대응 응답이 없는 예제 케이스를 처음부터 건너뛰게 만든 것이다. 이번 변경이 만든 skip 이 아니다.
- `characterization/__snapshots__/item-bank.test.js.snap` 은 이번 범위에서 **새로 추가**된 파일이다. 기존 스냅샷을 고친 흔적은 없다(변경된 기존 파일은 서비스 · 컨트롤러 · 리포지토리와 그 테스트 6개뿐).
- `modern/web` 은 변경이 없어 실행하지 않았다.

## 동작 보존 대조 (`search.php` ↔ 새 코드)

아래 항목은 레거시 줄과 새 코드를 직접 대조했다. 차이를 찾지 못했다.

| 규칙 | 레거시 | 새 코드 |
|---|---|---|
| 난이도 비어 있으면 `level < 5` | `search.php:215` | `ItemSearchSpecifications.java:57` |
| 난이도 `^[1-5]$` 가 아니면 `(int)` 변환값으로 `=` 비교 | `search.php:217-229` | `ItemSearchCondition.java:88-95` |
| 태그: EXISTS · 정확히 일치 · 50자 자르기 | `search.php:232-244` | `ItemSearchSpecifications.java:67-75`, `ItemSearchCondition.java:30` |
| 정렬 5종 + 기본 + 알 수 없는 값, 방향 기본값이 기준마다 다름 | `search.php:276-326` | `ItemSearchSort.java:46-56` |
| 페이지: 숫자 형식 아니면 1, 1 미만 1, 999 초과 999, 20건 | `search.php:336-348` | `ItemSearchCondition.java:98-106` |
| 공개 문항 `status = 'A'` | 뷰 `v_item_public` | `ItemSearchSpecifications.java:34` (`ItemStatus.ACTIVE`) |

## 이슈 목록

### 1. [경고→반려 사유] 빌드 산출물 54개가 커밋됨
- 위치: `modern/api/bin/` 전체 (`git ls-files modern/api/bin` = 54개). 예: `modern/api/bin/main/application.yml`, `modern/api/bin/main/com/example/item/ItemService.class`
- 근거: `.gitignore` 에 `bin/` 이 없다. IDE 컴파일 산출물(`.class` 와 `application.yml` · `application-test.yml` 복사본)이 이관 커밋(`d577f8e`)에 함께 들어갔다. 요청 "엔드포인트 1개 이관"으로 설명되지 않는다(체크리스트 3번 두 번째 항목 미충족).
- 부가 위험: `bin/main/application.yml` 이 설정 파일(DB 접속 · 커넥션 풀)의 복사본이다. 원본이 바뀌면 복사본이 낡은 값을 가진 채 저장소에 남는다. 접속 비밀번호는 실습 더미(`app-pass`)라 CLAUDE.md 예외에 해당한다.
- 수정 방향: `git rm -r --cached modern/api/bin` 으로 추적을 끊고 `.gitignore` 에 `bin/` 을 추가한다(`.gitignore` 는 요청 범위 밖이므로 사람이 승인한 뒤에). 작업 트리의 `legacy/*/bin/` 도 같은 이유로 추적하지 않는 것이 맞다(현재는 untracked).

### 2. [경고→반려 사유] 이관과 무관한 문서 · 설정이 같은 diff 에 있음
- 위치:
  - `docs/assignment/ARCHITECTURE.md`, `BUSINESS-RULES.md`, `ERD.md` (455줄) — 과제 배포 모듈 문서. 이번 이관 대상은 item-bank 다.
  - `CLAUDE.md`, `.claude/skills/convention-check/SKILL.md`, `.claude/skills/document-module/SKILL.md` — 작업 환경 설정.
- 근거: 체크리스트 3번 "변경 파일 목록의 모든 파일이 요청 문장으로 설명된다" 미충족. 이 파일들은 코드 이관이 아니라 앞선 준비 커밋(`176b2b5`)에서 온 것이다.
- 참고: `docs/item-bank/*`(4개)와 `characterization/` 베이스라인(테스트 + 스냅샷)은 이관의 근거 · 안전망이라 범위 안으로 본다.
- 수정 방향: 준비 커밋과 이관 커밋을 PR 로 나눈다. 준비 커밋이 이미 `main` 에 반영돼야 한다면 `upstream/main` 을 갱신한 뒤 다시 검증한다.

### 3. [경고] `ItemSearchCondition` 이 웹 타입에 의존
- 위치: `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:9,41` — `org.springframework.util.MultiValueMap` 을 받아 조건을 만든다.
- 근거: 파라미터 해석(PHP 호환 규칙)이 도메인 조건 객체에 들어 있어, 컨트롤러 밖에서 재사용하거나 테스트하기 어렵다. 현재 동작에는 문제가 없고 CLAUDE.md 위반도 아니다.
- 수정 방향(제안 수준): 해석 로직을 `ItemSearchCondition` 밖의 파서로 빼는 것을 다음 변경에서 고려한다.

### 4. [제안] 의심 동작 보고 확인
- CLAUDE.md §5 는 레거시 버그로 보이는 동작을 고치지 않고 "의심 동작"으로 보고하라고 한다. 이관 코드는 그 동작을 그대로 옮겼다. 예: 난이도 기본 검색에서 5 제외(`ItemSearchSpecifications.java:12` 의 주석 "레거시 그대로"), `%` · `_` 미이스케이프(같은 파일 `keywordLike` 주석).
- 확인 필요: 의심 동작 목록이 `docs/item-bank/CROSS-CHECK.md` 등에 정리돼 있는지는 이번에 대조하지 않았다.

## 컨벤션 점검 요약

| 항목 | 결과 |
|---|---|
| 컨트롤러는 서비스만 주입, Repository · SQL 없음 | 준수 (`ItemController.java`) |
| 응답은 record DTO | 준수 (`ItemSearchResponse`, `ItemSearchRow`) |
| 조회 메서드 `@Transactional(readOnly = true)` | 준수 (클래스 레벨, `ItemService.java:13`) |
| 빈 catch · `System.out` · `printStackTrace` | 없음 (신규 코드). `catch` 는 기존 `ReportService.java:98` 한 곳뿐 |
| 와일드카드 import | 없음 |
| 숫자 리터럴 상수화 | 준수 (`PAGE_SIZE`, `MAX_PAGE`, `KEYWORD_MAX_LENGTH`, `DEFAULT_LEVEL_CEILING`) |
| 새 public 서비스 메서드 · 엔드포인트 테스트 | 있음 (`ItemServiceTest` 2건, `ItemControllerTest` 3건, `ItemRepositoryTest`, `ItemSearchConditionTest`) |
| 의존성 · 스키마 · `application.yml` 변경 | 없음 |

## 다음 단계

1. 이슈 1 · 2 를 정리한다(산출물 추적 해제, 변경 분리).
2. 정리 후 `upstream/main...HEAD` 가 이관 파일만 담으면 다시 `/verify` 를 실행한다. 코드 · 테스트 결과는 그대로 유지될 것으로 보이지만, 재검증에서 다시 실행해 확인한다.
