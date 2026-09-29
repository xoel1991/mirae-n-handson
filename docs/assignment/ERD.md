# 과제 배포(assignment) — ERD · 데이터 흐름

> 대상: `legacy/assignment-thymeleaf` · 분석 범위: 새 배포 화면(`GET /distributions/new`, `POST /distributions`)
> 선행 문서: `docs/assignment/ARCHITECTURE.md` (경로 줄임 규칙 동일)
> 테이블 정의는 모듈 폴더 밖 `db/mariadb/init/01-schema.sql:72-115` 에 있다. 2026-09-29 에 기동 중인 MariaDB 에 **SELECT 만** 보내 건수를 확인했다.

## 1. 테이블 목록 (범위 안에서 닿는 것)

| 테이블 | 정의 | 이 화면에서 | 비고 |
|---|---|---|---|
| `assignment` | `01-schema.sql:82-90` | 읽기 | `status CHAR(1) DEFAULT 'O'`, 주석 `O=진행 C=마감`(`:87`). DB 에는 `C` 2 · `O` 4 건 |
| `unit` | `01-schema.sql:11-18` | 읽기 | 과제 선택지에 단원 코드 표시용. 문항 은행과 공유 |
| `class` | `01-schema.sql:75-80` | 읽기 | 3건(`02-seed.sql:111-114`) |
| `distribution` | `01-schema.sql:92-103` | 읽기 · **쓰기(INSERT)** | PK 는 `id` 하나. `(assignment_id, class_id)` UNIQUE 제약 **없음**. 시드 8건 |
| `submission` | `01-schema.sql:105-115` | 닿지 않음 | 범위 밖(배포 목록 · 재배포에서 사용) |

- 트리거 · 프로시저 없음(`information_schema.TRIGGERS` 0건). 뷰는 문항 은행의 `v_item_public` 하나뿐이며 이 화면과 무관하다.

## 2. 테이블 관계

```mermaid
erDiagram
  unit ||--o{ assignment : "unit_id (fk_assignment_unit)"
  assignment ||--o{ distribution : "assignment_id (fk_distribution_assignment)"
  class ||--o{ distribution : "class_id (fk_distribution_class)"
  distribution ||--o{ submission : "distribution_id (범위 밖)"

  assignment {
    INT id PK
    VARCHAR title
    INT unit_id FK
    DATETIME due_at
    CHAR status "O C (코드는 X D 도 씀)"
  }
  class {
    INT id PK
    VARCHAR name
    VARCHAR teacher_id
  }
  distribution {
    INT id PK "MAX(id)+1 로 채번"
    INT assignment_id FK
    INT class_id FK
    DATETIME distributed_at
    TINYINT redistributed "INSERT 시 0"
  }
```

- 근거: FK `01-schema.sql:89`, `:101-102`, `:114`.

## 3. 읽기 · 쓰기 위치

### 3.1 테이블별

| 테이블 | 작업 | SQL 위치 | 호출 위치 |
|---|---|---|---|
| `assignment` + `unit`(LEFT JOIN) + `distribution`(서브쿼리) | SELECT, `status IN ('O','X','C')`, `due_at ASC, id ASC` | `dao/AssignmentDao.java:21-24`, `:34-39` | 폼 표시 `web/DistributionController.java:42` |
| 같은 조인 | SELECT `WHERE a.id = ?` | `dao/AssignmentDao.java:41-48` | 배포 `web/DistributionController.java:53`, `service/DistributionService.java:48` (**한 요청에 2번**) |
| `class` | SELECT 전체 `ORDER BY id ASC` | `dao/ClassDao.java:20-22` | 폼 표시 `web/DistributionController.java:43` |
| `class` | SELECT `WHERE id = ?` | `dao/ClassDao.java:24-30` | `service/DistributionService.java:52` |
| `distribution` | SELECT COUNT(*) `WHERE assignment_id = ? AND class_id = ?` | `dao/DistributionDao.java:47-52` | `service/DistributionService.java:59` |
| `distribution` | SELECT `COALESCE(MAX(id), 0)` | `dao/DistributionDao.java:54-57` | `service/DistributionService.java:62` |
| `distribution` | **INSERT** `(id, assignment_id, class_id, distributed_at, redistributed=0)` | `dao/DistributionDao.java:59-63` | `service/DistributionService.java:63` |

- 이 화면에는 UPDATE · DELETE 가 없다.
- `AssignmentDao` 의 `class_cnt` 서브쿼리(`:23`)는 이 화면에서 쓰지 않는다. 과제 목록 화면만 쓴다(`assignments.html:27`).

### 3.2 화면별 흐름 요약

| 단계 | 흐름 |
|---|---|
| 폼 표시 | `assignment`(O · X · C) + `class` 전체 조회 → `AppClock.now()`(기본 2026-09-15 00:00) 와 비교해 마감 지난 과제 선택지를 비활성화 |
| 배포 등록 | `assignment` 조회 → 마감 검사(컨트롤러) → [트랜잭션 시작] `assignment` 재조회 → `class` 조회 → 삭제 상태 검사 → 중복 COUNT → MAX(id)+1 → INSERT → [커밋] → `/distributions` 로 이동 |

## 4. 미확인

1. `assignment.status` 의 `X`(연장) · `D`(삭제)는 코드에만 있고 스키마 주석(`01-schema.sql:87`)과 시드 · 현재 DB 에는 없다. 누가 이 값을 넣는지 모듈 안에서 찾지 못했다(모듈에 `assignment` 쓰기 SQL 0건).
2. 기존 데이터에 이미 같은 (과제 2, 1반) 배포가 2건 있다(`02-seed.sql:133-134`, DB 조회로도 확인). 이 조합은 `countByAssignmentAndClass` 가 2를 돌려주므로 새 배포가 막힌다. 행이 생긴 경로는 ARCHITECTURE.md §7 #4 참고.
3. `distribution.id` 채번(`MAX(id)+1`)은 동시에 두 요청이 들어오면 같은 번호를 받을 수 있다. 이때 PK 충돌이 `DataAccessException` 이 되는지는 실행해 보지 않았다.

## 5. 주의 — 주석과 코드가 다른 곳

| 위치 | 주석 | 코드 |
|---|---|---|
| `01-schema.sql:87` | `O=진행 C=마감` 두 값 | `X` 를 연장으로(`web/DistributionController.java:61`), `D` 를 삭제로(`service/DistributionService.java:56`, `dao/AssignmentDao.java:26`) 쓴다 |
| `AppClock.java:8` | "2024-03 QA 기간 중 임시로 고정" | 고정값은 `2026-09-15 00:00:00`(`:9`) — 주석의 시기와 값이 맞지 않는다 |
