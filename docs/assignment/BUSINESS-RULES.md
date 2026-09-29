# 과제 배포(assignment) — 비즈니스 규칙 후보

> 대상: `legacy/assignment-thymeleaf` · 분석 범위: **새 배포 화면**(`GET /distributions/new`, `POST /distributions`)과 그 화면이 부르는 코드
> 선행 문서: `docs/assignment/ARCHITECTURE.md`, `docs/assignment/ERD.md`
> 근거는 `파일:줄번호` 이다. 자바 파일은 `legacy/assignment-thymeleaf/src/main/java/com/example/assign/` 접두어를, 템플릿은 `legacy/assignment-thymeleaf/src/main/resources/templates/` 접두어를 줄였다. 스키마 · 시드는 `db/mariadb/init/` 을 줄여 `01-schema.sql:87` 처럼 적었다.
> **실행 확인 없음**: 과제 앱이 기동되어 있지 않았고, 배포 등록(POST)은 DB 에 쓰기 때문에 실행하지 않았다. DB 에는 SELECT 만 보냈다.

## 범위 메모

- 배포 목록 · 재배포 · 과제 목록 화면의 규칙은 적지 않았다. 재배포(`service/DistributionService.java:71-`)는 별도 범위로 분석해야 한다.

---

## 1. 폼 표시 — 선택지

### BR-01 과제 선택지에는 진행 · 연장 · 마감 상태 과제만 나온다
- **규칙**: 과제 `status` 가 `'O'` · `'X'` · `'C'` 인 과제만 선택지에 나오고(그 밖의 값, 예: `'D'` 는 빠진다), 마감 시각 오름차순, 같으면 ID 오름차순으로 정렬한다.
- **근거**: `dao/AssignmentDao.java:36-37`, 호출 `web/DistributionController.java:42`
- **근거 코드**:
  ```java
  + " WHERE a.status IN ('O', 'X', 'C') "      // dao/AssignmentDao.java:36
  + " ORDER BY a.due_at ASC, a.id ASC";        // dao/AssignmentDao.java:37
  ```
- **확신도**: 확실
- **비고**: `'X'` · `'D'` 의 뜻은 코드에서만 읽힌다(ERD.md §5). 스키마 주석은 `O=진행 C=마감` 뿐이다(`01-schema.sql:87`).

### BR-02 마감이 지난 과제는 선택할 수 없다 — 연장(`'X'`) 과제는 예외 (화면 동작)
- **규칙**: 마감 시각이 현재 시각보다 앞선 과제의 선택지는 비활성화된다. 단 상태가 `'X'` 이면 선택할 수 있다. 마감 지난 과제 이름 뒤에는 `' [연장]'`(상태 `'X'`) 또는 `' [마감]'`(그 밖)이 붙는다.
- **근거**: `distribution_form.html:16`, `:18`
- **근거 코드**:
  ```html
  th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
  + (${a.dueAt != null and a.dueAt.isBefore(now)} ? (${a.status == 'X'} ? ' [연장]' : ' [마감]') : '')"></option>
  ```
- **확신도**: 확실
- **비고**:
  - 화면에서만 막는 규칙이다. 서버도 같은 조건을 따로 검사한다(BR-07).
  - 여기서 "현재 시각" `now` 는 `AppClock.now()` 이다(`web/DistributionController.java:44`, BR-05).
  - 표시 형식은 `"ID. 제목 (단원코드, 마감 MM-dd HH:mm)"` 이다(`distribution_form.html:17`).

### BR-03 학급 선택지에는 모든 학급이 ID 순으로 나온다
- **규칙**: 학급은 조건 없이 전체를 ID 오름차순으로 보여 주고, `"학급명 (교사ID)"` 로 표시한다.
- **근거**: `dao/ClassDao.java:21`, `distribution_form.html:25`
- **근거 코드**:
  ```java
  return jdbc.query("SELECT id, name, teacher_id FROM `class` ORDER BY id ASC", MAPPER);
  ```
  ```html
  <option th:each="c : ${classes}" th:value="${c.id}" th:text="${c.name} + ' (' + ${c.teacherId} + ')'"></option>
  ```
- **확신도**: 확실
- **비고**: 로그인 교사의 학급만 거르는 조건은 없다. 이 모듈에는 로그인 기능이 없다.

### BR-04 과제와 학급은 둘 다 골라야 제출된다 (화면 동작)
- **규칙**: 두 선택 상자 모두 `required` 이고 첫 선택지의 값이 빈 문자열이라, 고르지 않으면 브라우저가 제출을 막는다.
- **근거**: `distribution_form.html:11-12`, `:23-24`
- **근거 코드**:
  ```html
  <select id="assignmentId" name="assignmentId" required>
  <option value="">-- 과제 선택 --</option>
  ```
- **확신도**: 확실
- **비고**: 서버 쪽에는 빈 값 · 숫자가 아닌 값에 대한 처리가 없다(ARCHITECTURE.md §7 #3).

## 2. 배포 등록 — 검증

### BR-05 "현재 시각"은 기본적으로 2026-09-15 00:00:00 으로 고정되어 있다
- **규칙**: 마감 비교와 배포 시각 기록에 쓰는 현재 시각은, 시스템 속성 `app.clock` 이 `"system"` 일 때만 실제 시각(나노초 0)이고 그 밖에는 항상 `2026-09-15 00:00:00` 이다.
- **근거**: `AppClock.java:9`, `:14-19`
- **근거 코드**:
  ```java
  private static final String FIXED = "2026-09-15 00:00:00";
  if (sys != null && sys.equals("system")) {
      return LocalDateTime.now().withNano(0);
  }
  return LocalDateTime.parse(FIXED, FMT);
  ```
- **확신도**: 확실
- **비고**:
  - 주석은 "임시로 고정 … 아직 안 되돌림"이라고 하고 시기를 "2024-03"으로 적었지만, 값은 2026-09-15 이다(`AppClock.java:8`).
  - 고정 시각 기준으로 마감이 지난 시드 과제는 1 · 2 · 3번이다. 실제 시각(2026-09-29)이면 4번(`2026-09-25 23:59`)도 마감이 지난다(`02-seed.sql:120-125`). **시계 설정에 따라 배포 가능 여부가 바뀐다.**

### BR-06 존재하지 않는 과제는 배포할 수 없다
- **규칙**: `assignmentId` 로 과제를 찾지 못하면 "존재하지 않는 과제입니다." 를 띄우고 폼으로 돌아간다.
- **근거**: `web/DistributionController.java:53-57`
- **근거 코드**:
  ```java
  AssignmentRow a = assignmentDao.findById(assignmentId);
  if (a == null) {
      ra.addFlashAttribute("error", "존재하지 않는 과제입니다.");
  ```
- **확신도**: 확실
- **비고**: 서비스도 같은 검사를 하고 메시지에 ID 를 붙인다(`service/DistributionService.java:49-51`, `"존재하지 않는 과제입니다. (id=" + assignmentId + ")"`). 컨트롤러가 먼저 걸러서, 이 화면에서 서비스 쪽 문구는 두 조회 사이에 행이 사라진 경우에만 나온다.

### BR-07 마감이 지난 과제는 배포할 수 없다 — 연장(`'X'`) 상태만 예외
- **규칙**: 과제 마감 시각이 현재 시각(BR-05)보다 앞서고 상태가 `'X'` 가 아니면, `"마감(yyyy-MM-dd HH:mm)이 지난 과제는 배포할 수 없습니다. [제목]"` 을 띄우고 폼으로 돌아간다. 마감 시각과 현재 시각이 같으면 배포할 수 있다(`isBefore`).
- **근거**: `web/DistributionController.java:58-65`, 날짜 형식 `AppClock.java:12`, `:26`
- **근거 코드**:
  ```java
  // 마감 지난 과제는 새 배포 불가. 연장(X) 상태만 예외
  if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
      if (!"X".equals(a.getStatus())) {
          ra.addFlashAttribute("error", "마감(" + AppClock.fmt(a.getDueAt()) + ")이 지난 과제는 배포할 수 없습니다. [" + a.getTitle() + "]");
  ```
- **확신도**: 확실
- **비고**:
  - 트랜잭션 밖(컨트롤러)에서 검사한다. 서비스 `distribute` 는 마감을 보지 않는다(`service/DistributionService.java:46-68`).
  - `due_at` 은 `NOT NULL` 이라(`01-schema.sql:86`) `getDueAt() != null` 조건은 현재 스키마에서는 항상 참이다.

### BR-08 존재하지 않는 학급에는 배포할 수 없다
- **규칙**: `classId` 로 학급을 찾지 못하면 `"존재하지 않는 학급입니다. (id=N)"` 을 띄우고 폼으로 돌아간다.
- **근거**: `service/DistributionService.java:52-55`, 메시지 전달 `web/DistributionController.java:69-71`
- **근거 코드**:
  ```java
  ClassRow c = classDao.findById(classId);
  if (c == null) {
      throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
  ```
- **확신도**: 확실
- **비고**: 없음

### BR-09 삭제(`'D'`) 상태 과제는 배포할 수 없다
- **규칙**: 과제 상태가 `'D'` 이면 "삭제된 과제는 배포할 수 없습니다." 를 띄우고 폼으로 돌아간다.
- **근거**: `service/DistributionService.java:56-58`
- **근거 코드**:
  ```java
  if ("D".equals(a.getStatus())) {
      throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
  ```
- **확신도**: 확실
- **비고**:
  - `'D'` 과제는 선택지에 없으므로(BR-01) 이 검사는 POST 를 직접 보낼 때만 걸린다.
  - 검사 순서상 마감이 지난 `'D'` 과제는 BR-07 문구가 먼저 나온다.
  - 현재 DB 에 `'D'` 과제는 없다(ERD.md §1).

### BR-10 마감(`'C'`) 상태 자체는 배포를 막지 않는다
- **규칙**: 상태로 배포를 막는 검사는 `'X'`(마감 검사 예외)와 `'D'`(금지) 두 가지뿐이다. 상태가 `'C'` 라도 마감 시각이 지나지 않았으면 배포된다.
- **근거**: 상태 검사 위치 `web/DistributionController.java:61`, `service/DistributionService.java:56`, 선택지 포함 `dao/AssignmentDao.java:36`
- **근거 코드**:
  ```java
  if (!"X".equals(a.getStatus())) {            // web/DistributionController.java:61
  if ("D".equals(a.getStatus())) {             // service/DistributionService.java:56
  ```
- **확신도**: 확실 (코드 동작) · 의도는 확인 필요
- **비고**: 스키마 주석은 `C=마감`(`01-schema.sql:87`)이지만 코드는 "마감"을 상태가 아니라 `due_at` 으로 판단한다. 현재 `'C'` 과제 두 건(1 · 2번)은 마감 시각도 지나서 실제로는 BR-07 에 걸린다.

### BR-11 같은 과제를 같은 학급에 두 번 배포할 수 없다
- **규칙**: 같은 (과제, 학급) 배포가 한 건이라도 있으면 "이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요." 를 띄우고 폼으로 돌아간다.
- **근거**: `service/DistributionService.java:59-61`, `dao/DistributionDao.java:46-52`
- **근거 코드**:
  ```java
  if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
  // 같은 학급 + 같은 과제 는 한 건만 존재해야 한다 (UNIQUE 제약은 없음, 여기서 막는다)
  "SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?",
  ```
- **확신도**: 확실
- **비고**:
  - DB 에 UNIQUE 제약이 없다(`01-schema.sql:92-103`). 애플리케이션 검사만으로 막는다.
  - 재배포된 건(`redistributed = 1`)도 세므로, 재배포 여부와 관계없이 막힌다.
  - 기존 데이터는 이미 이 규칙을 어긴다. (과제 2, 1반) 이 2건이다(`02-seed.sql:133-134`, ERD.md §4 #2).

## 3. 배포 등록 — 저장

### BR-12 배포 번호는 현재 최대 번호 + 1 이다
- **규칙**: 새 배포 ID 는 `distribution` 의 최대 ID + 1 이다(행이 없으면 1).
- **근거**: `dao/DistributionDao.java:54-57`, 호출 `service/DistributionService.java:62`
- **근거 코드**:
  ```java
  Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);
  return (max == null ? 0L : max.longValue()) + 1L;
  ```
- **확신도**: 확실
- **비고**: 동시 요청 시 같은 번호를 받을 수 있다(ERD.md §4 #3, 실행 미확인).

### BR-13 새 배포는 현재 시각 · 재배포 아님(0) 으로 저장된다
- **규칙**: 배포 시각은 `AppClock.now()`(BR-05), `redistributed` 는 `0` 으로 INSERT 한다.
- **근거**: `service/DistributionService.java:63`, `dao/DistributionDao.java:61`
- **근거 코드**:
  ```java
  int n = distributionDao.insert(id, assignmentId, classId, AppClock.now());
  "INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)",
  ```
- **확신도**: 확실
- **비고**: 기본 설정이면 **모든 새 배포의 배포 시각이 `2026-09-15 00:00:00` 으로 같다.**

### BR-14 저장된 행이 1건이 아니면 실패로 처리한다
- **규칙**: INSERT 결과 행 수가 1이 아니면 "배포 저장에 실패했습니다." 를 띄우고 폼으로 돌아간다.
- **근거**: `service/DistributionService.java:64-66`
- **근거 코드**:
  ```java
  if (n != 1) {
      throw new IllegalStateException("배포 저장에 실패했습니다.");
  ```
- **확신도**: 확실
- **비고**: 없음

### BR-15 서비스의 검사와 저장은 한 트랜잭션이다
- **규칙**: `distribute` 안의 과제 · 학급 조회, 삭제 · 중복 검사, 채번, INSERT 는 한 트랜잭션으로 실행되고, 예외가 나면 되돌린다.
- **근거**: `service/DistributionService.java:46-47`, `build.gradle:27`(spring-boot-starter-jdbc)
- **근거 코드**:
  ```java
  @Transactional
  public long distribute(long assignmentId, long classId) {
  ```
- **확신도**: 확실
- **비고**: 컨트롤러의 존재 · 마감 검사(BR-06, BR-07)는 트랜잭션 밖이다. 트랜잭션만으로는 BR-11 · BR-12 의 동시 요청 문제를 막지 못할 수 있다(격리 수준 미확인).

## 4. 배포 등록 — 결과 안내

### BR-16 성공하면 배포 번호를 알리고 배포 목록으로 이동한다
- **규칙**: 성공 시 `"배포가 등록되었습니다. (배포 #N)"` 을 띄우고 `/distributions` 로 이동한다.
- **근거**: `web/DistributionController.java:67-68`, `:76`
- **근거 코드**:
  ```java
  ra.addFlashAttribute("message", "배포가 등록되었습니다. (배포 #" + id + ")");
  return "redirect:/distributions";
  ```
- **확신도**: 확실
- **비고**: 없음

### BR-17 실패하면 오류 문구와 함께 새 배포 폼으로 돌아간다
- **규칙**: 검증 실패(`IllegalArgumentException` · `IllegalStateException`)는 예외 메시지를, DB 오류(`DataAccessException`)는 "DB 오류로 배포에 실패했습니다." 를 띄우고 `/distributions/new` 로 돌아간다.
- **근거**: `web/DistributionController.java:69-75`
- **근거 코드**:
  ```java
  } catch (IllegalArgumentException | IllegalStateException e) {
      ra.addFlashAttribute("error", e.getMessage());
  } catch (DataAccessException e) {
      ra.addFlashAttribute("error", "DB 오류로 배포에 실패했습니다.");
  ```
- **확신도**: 확실
- **비고**:
  - 폼으로 돌아가면 선택했던 값은 유지되지 않는다(redirect 후 새로 그림, flash 에 선택값 없음).
  - `try` 밖인 `:53` 의 과제 조회에서 DB 오류가 나면 `DbErrorAdvice` 가 `db_error` 화면을 보여 준다(`web/DbErrorAdvice.java:11-16`).

---

## 5. 매직 넘버 · 매직 값

| 값 | 뜻 | 위치 |
|---|---|---|
| `'O'` | 진행 | `dao/AssignmentDao.java:36`, `01-schema.sql:87` |
| `'X'` | 연장 (마감 검사 예외) | `web/DistributionController.java:59-61`, `distribution_form.html:16`, `:18` |
| `'C'` | 마감 (스키마 주석). 코드는 선택지 포함만 함 | `dao/AssignmentDao.java:36`, `01-schema.sql:87` |
| `'D'` | 삭제 | `service/DistributionService.java:56`, `dao/AssignmentDao.java:26` |
| `"2026-09-15 00:00:00"` | 고정 현재 시각 | `AppClock.java:9` |
| `"system"` | 실제 시각 사용 스위치(`app.clock`) | `AppClock.java:15-16` |
| `0` | 새 배포의 `redistributed` | `dao/DistributionDao.java:61` |
| `'MM-dd HH:mm'` / `yyyy-MM-dd HH:mm` | 선택지 마감 표시 / 오류 문구 마감 표시 | `distribution_form.html:17`, `AppClock.java:12` |

## 6. 주석과 코드가 다른 곳 · 같은 규칙이 두 곳에 구현된 곳

1. `01-schema.sql:87` 주석은 `O` · `C` 두 값만 적었지만 코드는 `X` · `D` 도 쓴다(BR-01, BR-07, BR-09).
2. `AppClock.java:8` 주석의 "2024-03 QA" 와 고정값 2026-09-15 가 맞지 않는다(BR-05).
3. 마감 검사가 화면(`distribution_form.html:16`)과 컨트롤러(`web/DistributionController.java:60-61`)에 따로 있다. 조건은 같다(BR-02, BR-07).
4. 과제 존재 검사가 컨트롤러(`web/DistributionController.java:54`)와 서비스(`service/DistributionService.java:49`)에 따로 있고 문구가 다르다(BR-06).
5. 시드 주석 "4번은 2번 과제를 1반에 재배포한 이력"(`02-seed.sql:128`)은 현재 재배포 코드(행 추가 없이 UPDATE)와 맞지 않는다(ARCHITECTURE.md §7 #4).

## 7. 검증 이력

| 날짜 | 대상 | 방법 | 판정 | 조치 |
| --- | --- | --- | --- | --- |
| | | | | |

- 아직 사람이 점검한 규칙이 없다. 표본 점검 후 이 표에 적는다.
