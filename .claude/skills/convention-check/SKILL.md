---
name: convention-check
description: 변경된 코드(또는 지정한 경로)가 CLAUDE.md 의 코딩 컨벤션과 금지 사항을 지키는지 점검해 위반 목록을 표로 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 곳 있나 봐줘" 같은 요청에 쓴다.
argument-hint: "[점검할 파일 또는 디렉터리 경로]"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

- 코드를 직접 고치지 않는다. 점검 결과만 보고한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기

- `$ARGUMENTS` 에 경로가 있으면 그 경로만 점검한다(디렉터리면 Glob 으로 파일을 모은다).
- 없으면 변경 파일을 모은다.
  - `git status --porcelain` — 수정 파일과 아직 add 하지 않은 새 파일(`??`)
  - `git diff --name-only HEAD` — 스테이징 여부와 관계없는 변경 파일
- 삭제된 파일은 8번 항목 판정에만 쓰고 내용 점검은 건너뛴다.
- 대상이 없으면 "점검할 변경 없음"이라고 쓰고 끝낸다.

## 2. 점검 항목 (위반이면 "예")

| # | 항목 | 근거 |
|---|---|---|
| 1 | 컨트롤러 파일에 `Repository` 주입, `JdbcTemplate`, SQL 문자열이 있는가 | CLAUDE.md:42 |
| 2 | 컨트롤러 메서드 반환 타입이 엔티티인가(`*Response` record 가 아님) | CLAUDE.md:44 |
| 3 | 서비스가 다른 도메인 패키지의 `Repository` 를 주입받는가 | CLAUDE.md:43 |
| 4 | 빈 `catch` 블록이 있거나, 컨트롤러 안에서 try-catch 로 상태 코드를 만드는가 | CLAUDE.md:49-50 |
| 5 | `System.out.println` · `System.err.println` · `e.printStackTrace()` 가 있는가 | CLAUDE.md:54 |
| 6 | 서비스 코드에서 `Clock` 인자 없이 `now()` 를 호출하는가(`common/ErrorResponse` 는 예외) | CLAUDE.md:46 |
| 7 | 와일드카드 import 가 있거나, `modern/web` 컴포넌트 · 훅에서 `fetch` 를 직접 호출하는가 | CLAUDE.md:66, 69 |
| 8 | 먼저 물어야 하는 영역이 바뀌었는가: `legacy/`, `db/`, 마이그레이션 파일, `build.gradle` · `package.json` 의존성, `__snapshots__/` | CLAUDE.md:74-76, 81 |

- 각 파일은 Read 로 열어 줄번호를 확인하고, Grep 은 후보를 찾는 데만 쓴다.
- 8번은 파일 경로와 `git diff` 내용으로 판정한다. 의존성은 `dependencies` 블록이 바뀐 경우만 해당한다.
- 새 public 서비스 메서드나 새 엔드포인트가 있는데 대응 테스트가 대상에 없으면 "확인 필요"로 남긴다(CLAUDE.md:59).

## 3. 출력 형식 (순서 고정)

### 판정 요약

- 점검 대상: 파일 N개(목록)
- 항목별 판정: `1 아니오 · 2 예 · …` 한 줄
- 위반 N건 · 확인 필요 N건

### 위반 목록

| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/.../ItemController.java:27` | 1. 컨트롤러에서 Repository 주입 | `ItemService` 를 주입받고 조회 로직을 서비스로 옮긴다 |

- 위반이 없으면 표 대신 "위반 없음"이라고 쓴다.
- "확인 필요" 항목은 표 아래에 `파일 — 이유` 형식으로 따로 적는다.
