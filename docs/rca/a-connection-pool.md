# RCA: a-connection-pool

## 요약

- **무슨 일**: 2026-09-16 14:37:52 ~ 14:52 KST(약 14분), API 커넥션 풀(5개)이 고갈돼 문항 · 단원 · 리포트 API 가 500 · 502 · 504 를 냈다(5xx 186건, 14:40:52 ~ 14:51:47).
- **원인**: 학급 리포트 API(`ReportService.buildClassReport`)가 DB 조회를 마친 뒤에도 트랜잭션 안에서 SHA-256 200,000회 서명 루프를 돌아, 요청마다 커넥션을 15~21초씩 쥐었다. 14:38 부터 리포트 요청이 평소의 5~10배(분당 10~19건)로 늘자 풀 5개가 모두 묶였다.
- **확신 수준**: 중간. 로그 · 코드 · 설정은 같은 메커니즘을 가리키지만, 서명 루프 실측 · 운영 배포 버전 · 리포트 요청 급증 원인은 확인하지 못했다(9절).
- **조치**: 서명을 트랜잭션 밖으로 옮긴다(6절). 풀 크기 변경은 수정안이 아니다.

## 1. 수집 범위

대상: `incident-logs/a-connection-pool/` (모든 파일을 `wc` · `head` · `tail` · `grep` · `awk` 집계로만 확인. 파일을 통째로 읽지 않음)

### 1.1 파일별 기록 기간 · 형식 · 타임존

| 파일 | 줄 수 | 기록 기간(원문 표기) | 기록 기간(KST) | 시각 형식 | 타임존 |
| --- | ---: | --- | --- | --- | --- |
| `app.log` | 5,371 | `2026-09-16T06:43:56.753+09:00` ~ `2026-09-16T15:29:51.760+09:00` | 06:43:56 ~ 15:29:51 | ISO 8601, 밀리초 | `+09:00` 명시 (KST) |
| `nginx-access.log` | 21,086 | `[16/Sep/2026:06:43:56 +0900]` ~ `[16/Sep/2026:15:29:59 +0900]` | 06:43:56 ~ 15:29:59 | nginx combined | `+0900` 명시 (KST) |
| `nginx-error.log` | 168 | `2026/09/16 09:12:03` ~ `2026/09/16 14:51:47` | 09:12:03 ~ 14:51:47 | `YYYY/MM/DD HH:MM:SS` | **표기 없음** → KST로 판단 (아래) |
| `mariadb-slow.log` | 21 (쿼리 3건) | `# Time: 260916  0:14:09` ~ `# Time: 260916  5:37:52` | 09:14:09 ~ 14:37:52 | `# Time: YYMMDD H:MM:SS` + `SET timestamp=<epoch>` | **UTC** (아래) |

### 1.2 KST 변환 규칙

- `app.log`, `nginx-access.log`: 이미 KST. 변환 없음.
- `mariadb-slow.log`: `# Time:` 값에 **+9시간**. 근거: 같은 항목의 `SET timestamp` epoch 를 UTC 로 풀면 `# Time:` 과 일치함(초 단위 2~10초 차이는 쿼리 시작 시각과 기록 시각의 차이로, 선후 판단에 쓰지 않음).
  - `mariadb-slow.log:1` `0:14:09` ↔ `:6` epoch 1789517647 = 00:14:07 UTC → KST 09:14:09
  - `mariadb-slow.log:8` `2:02:42` ↔ `:13` epoch 1789524160 = 02:02:40 UTC → KST 11:02:42
  - `mariadb-slow.log:15` `5:37:52` ↔ `:20` epoch 1789537062 = 05:37:42 UTC → KST 14:37:52
- `nginx-error.log`: 변환 없음(KST로 간주). 근거: 첫 `[error]`(`nginx-error.log:6`, 14:41:06)와 마지막 줄(14:51:47)이 `nginx-access.log` 의 5xx 첫 줄(`nginx-access.log:18918`, 14:40:52) · 마지막 줄(`nginx-access.log:19381`, 14:51:47)과 같은 시각대에 맞물림. UTC였다면 9시간 어긋나 05시대여야 하나 access 로그는 06:43 부터 시작함.

### 1.3 분 단위 이상 건수

**평소 수준(06:43 ~ 14:36)**

| 파일 | 집계 대상 | 평소 수준 |
| --- | --- | --- |
| `app.log` | `WARN` · `ERROR` | WARN 시간당 0~4건(08시 1, 09시 3, 10시 4, 11시 1, 12시 2, 13시 1, 14:00~14:36 4건), ERROR 0건 |
| `nginx-access.log` | 5xx | 06~13시 0건 (분당 요청 약 35~50건) |
| `nginx-error.log` | `[warn]` · `[error]` | 09:12, 13:05 에 `[warn]` 각 1건(`nginx-error.log:1`, `:4`), `[error]` 0건 |
| `mariadb-slow.log` | 슬로우 쿼리 | 09:14, 11:02 각 1건 |

**이상 구간(14:37 ~ 14:52)**

| 분(KST) | app WARN | app ERROR | app 타임아웃 줄 | access 전체 | access 5xx | access 504 | nginx-error warn+error | slow |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 14:37 | 1 | 0 | 0 | 50 | 0 | 0 | 0 | 1 |
| 14:38 | 2 | 0 | 0 | 41 | 0 | 0 | 0 | 0 |
| 14:39 | 3 | 0 | 0 | 42 | 0 | 0 | 0 | 0 |
| 14:40 | 15 | 2 | 1 | 42 | 1 | 0 | 0 | 0 |
| 14:41 | 19 | 12 | 6 | 42 | 9 | 3 | 3 | 0 |
| 14:42 | 13 | 4 | 2 | 41 | 20 | 5 | 20 | 0 |
| 14:43 | 24 | 20 | 10 | 58 | 17 | 5 | 8 | 0 |
| 14:44 | 13 | 4 | 2 | 34 | 13 | 6 | 15 | 0 |
| 14:45 | 17 | 16 | 8 | 39 | 27 | 5 | 22 | 0 |
| 14:46 | 16 | 8 | 4 | 51 | 26 | 6 | 25 | 0 |
| 14:47 | 14 | 10 | 5 | 38 | 14 | 3 | 10 | 0 |
| 14:48 | 14 | 8 | 4 | 42 | 15 | 5 | 15 | 0 |
| 14:49 | 19 | 14 | 7 | 42 | 23 | 8 | 20 | 0 |
| 14:50 | 12 | 0 | 0 | 34 | 18 | 7 | 22 | 0 |
| 14:51 | 4 | 0 | 0 | 46 | 3 | 2 | 3 | 0 |
| 14:52 | 1 | 0 | 0 | 47 | 0 | 0 | 0 | 0 |

- "app 타임아웃 줄"은 시각이 찍힌 줄 중 `timeout` · `timed out` 을 포함하는 줄 수(스택 트레이스 줄 제외).
- 구간 합계: access 5xx 186건(500 49 · 502 82 · 504 55), nginx-error `[error]` 137건 · `[warn]` 26건, app ERROR 98건.
- 14:53 ~ 15:29: access 5xx 0건, app WARN · ERROR 0건. 분당 요청 수는 구간 전후와 같은 수준(약 35~50건).

### 1.4 제안 구간

| 구분 | 시각(KST) | 근거 |
| --- | --- | --- |
| 이상 시작 | **14:37** | app WARN 이 평소(시간당 0~4건)를 벗어나 14:37~14:39 에 6건(`app.log:1243` 14:37:52 부터). 같은 분에 슬로우 쿼리 1건(`mariadb-slow.log:15`) |
| 오류 본격화 | 14:40:52 | 첫 app ERROR(`app.log:1522`)와 첫 access 5xx(`nginx-access.log:18918`)가 같은 초 |
| 이상 끝 | **14:52** | 마지막 5xx · nginx-error(14:51:47, `nginx-access.log:19381`, `nginx-error.log:168`), 마지막 app ERROR 14:49:51(`app.log:4981`), app WARN 14:52 1건 후 0건 |

### 1.5 앞뒤 여유

- **앞쪽 37분: 14:00 부터.** 14:00~14:36 사이에도 app WARN 4건(`app.log:1122` 14:02, `:1154` 14:11, `:1165` 14:14, `:1229` 14:33)이 있어 평소와 같은 수준인지 타임라인에서 확인해야 함. 더 앞의 슬로우 쿼리 2건(KST 09:14, 11:02)과 nginx `[warn]` 2건(09:12, 13:05)은 구간 밖이지만 비교용으로 타임라인 앞에 참고 항목으로만 둠.
- **뒤쪽 10분: 15:02 까지.** 14:52 이후 회복을 확인하는 데 충분함(5xx · ERROR 0건 유지 확인용).
- 결과 분석 구간: **2026-09-16 14:00:00 ~ 15:02:00 KST** (슬로우 로그는 UTC 05:00:00 ~ 06:02:00).

> 원인 추측은 이 절에 쓰지 않음. 해석은 3절에서 함.

## 2. 타임라인

대상: 2026-09-16 14:00:00 ~ 15:02:00 KST. `mariadb-slow.log` 는 UTC 에 +9시간을 더해 적음. 해석은 쓰지 않음.

**구간 앞 참고(평소 수준 비교용)**: 슬로우 쿼리 09:14:09(`mariadb-slow.log:1`, 2.3초) · 11:02:42(`mariadb-slow.log:8`, 2.0초), nginx `[warn]` 09:12:03(`nginx-error.log:1`) · 13:05:51(`nginx-error.log:4`). `/api/classes/*/report` 요청은 07~13시 시간당 2~11건.

| 시각(KST) | 출처(파일:줄번호) | 사건 요약 | 원문 발췌(80자 이내) |
| --- | --- | --- | --- |
| 14:02:40 | `app.log:1122` | 재배포 상태 충돌 WARN. 이후 14:11 · 14:14 · 14:33 에 1건씩(`:1154`, `:1165`, `:1229`) | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=1` |
| 14:04:33 | `app.log:1129` | 배포 7 재배포. 14:19:38 에 같은 배포 재배포 1건 더(`:1182`) | `redistributed distribution 7 (assignment 5, class 3) reason=null` |
| 14:05:42 | `nginx-access.log:17356` | 14시대 첫 `/report` 요청(200). 14:18 까지 6건, 14:19~14:37 0건 | `"GET /api/classes/2/report HTTP/1.1" 200` |
| 14:37:52 | `mariadb-slow.log:15` (UTC 5:37:52) | 슬로우 쿼리 9.8초, Rows_examined 1,284,310. 이날 슬로우 로그 3건 중 최장. 호스트 `API-1` | `select s1_0.id,…from submission s1_0 where s1_0.distribution_id=5 order by…` |
| 14:37:52 | `app.log:1243` | 첫 Hikari 커넥션 누수 감지 WARN. 스택에 `ReportService.buildClassReport`(`app.log:1253`). (슬로우 로그와 같은 초, 선후 불확실) | `Connection leak detection triggered for …Connection@6e8f14d3 on thread http-nio-8080-exec-6` |
| 14:38:01 | `nginx-access.log:18801` · `app.log:1258` · `app.log:1259` | `/report` 요청 200, 리포트 생성 INFO, 같은 커넥션 반환(unleaked). (세 줄 2ms 이내, 선후 불확실) | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:38:29 | `app.log:1278` | 배포 5 재배포 | `redistributed distribution 5 (assignment 3, class 3) reason=null` |
| 14:38 ~ 14:39 | `app.log:1261` ~ `:1331` | 누수 감지 WARN 2건/분 → 3건/분. 커넥션 3개(`@6e8f14d3`, `@2ac19f07`, `@71d4b8e2`)로 늘어남. `/report` 요청 3건/분(`nginx-access.log:18815` 등) | `Connection leak detection triggered for …Connection@71d4b8e2 on thread http-nio-8080-exec-4` |
| 14:40 | `app.log` 집계 | 누수 감지 WARN 14건/분, `/report` 요청 10건/분 | (집계) |
| 14:40:52 | `app.log:1521` · `:1522` | **첫 커넥션 획득 타임아웃 ERROR.** 이후 14:49:51(`app.log:4980`)까지 49건, 분당 2~10건 | `itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| 14:40:52 | `app.log:1523` · `nginx-access.log:18918` | 같은 요청 500 응답. 첫 5xx(선후 불확실) | `"GET /api/units/M6-2/items HTTP/1.1" 500 162` |
| 14:41:06 | `nginx-error.log:6` · `nginx-access.log:18933` | 첫 upstream 타임아웃(upstream `API-1`), 504 응답. 이후 55건, 3~8건/분 | `upstream timed out (110: Connection timed out) while reading response header` |
| 14:42:01 | `nginx-error.log:10` | upstream `API-1` 일시 제외 WARN. 이후 26건, 1~4건/분 | `upstream server temporarily disabled while reading response header from upstream` |
| 14:42:01 | `nginx-error.log:11` · `nginx-access.log:18970` | upstream `API-2` 연결 거부, 첫 502. 이후 14건, 1~2건/분 (선후 불확실) | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:42:04 | `nginx-error.log:12` | 살아 있는 upstream 없음. 이후 68건, 1~14건/분 | `no live upstreams while connecting to upstream` |
| 14:41 ~ 14:50 | `nginx-access.log` 집계 | 5xx 9~27건/분(500 49 · 502 82 · 504 55), `/report` 10~19건/분(클라이언트 4개). 전체 요청은 34~58건/분 | (집계) |
| 14:44:19 | `app.log:2929` | 배포 8 재배포 | `redistributed distribution 8 (assignment 6, class 2) reason=보강 수업 후 재배포` |
| 14:49:51 | `app.log:4980` | 마지막 커넥션 타임아웃 ERROR | `itembank-pool - Connection is not available, request timed out after 3000ms` |
| 14:51:37 | `app.log:5251` | 마지막 누수 감지 WARN | `Connection leak detection triggered for org.mariadb.jdbc.Connection@…` |
| 14:51:47 | `nginx-error.log:168` · `nginx-access.log:19381` | 마지막 upstream 오류 · 마지막 5xx (선후 불확실) | `upstream timed out (110: Connection timed out)` |
| 14:52 ~ 15:02 | `nginx-access.log:19393` 이후 | 5xx 0건, 분당 요청 35~47건. app WARN 은 14:52:09 재배포 상태 충돌 1건(`app.log:5269`) 뿐 | `state conflict: 마감된 과제는 재배포할 수 없습니다` |

## 3. 가설과 검증

> 이 절은 가설만 세운다. 어느 가설이 맞는지는 아직 판정하지 않는다.

**증상과 원인을 나눈다.** 가장 많이 나온 `Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)`(`app.log:1522` 외 49건)와 nginx 의 `upstream timed out` · `no live upstreams`(`nginx-error.log:6`, `:12`)는 **증상**이다. "풀 5개가 모두 빌려 나가 돌아오지 않았다"는 사실만 말해 주고, *왜* 돌아오지 않았는지는 말해 주지 않는다. 아래 가설은 그 "왜"에 대한 후보다.

참고한 코드 · 설정(판정 근거가 아니라 확인 방법을 정하는 데 씀):
- `modern/api/src/main/resources/application.yml:14-18` — `maximum-pool-size: 5`, `connection-timeout: 3000`, `leak-detection-threshold: 10000`
- `modern/api/src/main/java/com/example/assignment/ReportService.java:41-71` — `@Transactional` 안에서 `sign()`(SHA-256 200,000회, `:23`, `:85-93`) 실행
- `db/mariadb/init/01-schema.sql:112` — `submission(distribution_id)` 인덱스 정의
- `nginx-access.log` 형식은 combined 이고 `$request_time` · `$upstream_addr` 가 없다(`nginx-access.log:18801`). 요청별 처리 시간 · upstream 은 access 로그만으로 알 수 없고 `nginx-error.log` 의 `upstream:` 필드로만 보인다.

### 3.1 가설 목록

| # | 계층 | 가설 한 문장 | 지지 근거(파일:줄번호) | 반증 조건(이것이 확인되면 이 가설은 틀림) | 확인 방법 | 로그 실행 결과 판정(근거: 3.3) |
| --- | --- | --- | --- | --- | --- | --- |
| H1 | 애플리케이션 코드 | `ReportService.buildClassReport` 가 DB 조회를 끝낸 뒤에도 `@Transactional` 안에서 CPU 를 쓰는 서명 루프를 돌며 커넥션을 오래 쥐고 있어, `/report` 요청이 겹치면 풀 5개가 이 요청들에 묶인다. | 첫 누수 감지 스택이 `buildClassReport` 를 가리킴(`app.log:1243`, `:1253`). 누수 감지 커넥션이 1→3개로 늘어남(`app.log:1261`~`:1331`). 14:40 에 누수 WARN 14건/분과 `/report` 10건/분이 같이 증가(타임라인 14:40 행). 첫 타임아웃 시 `active=5, waiting=4`(`app.log:1522`). | ① 구간 안 누수 감지 WARN 스택 대부분이 `buildClassReport` 가 **아닌** 다른 메서드(예: `ItemService`, `DistributionService`)를 가리킨다. ② 사고 당시 배포된 버전에는 `sign()` 이 트랜잭션 밖에 있었다(또는 서명 루프가 없었다). ③ 서명 루프의 실제 소요 시간이 `leak-detection-threshold`(10초)·동시 요청 수에 비해 무시할 만큼 짧다. | ① `grep -A12 'Connection leak detection' incident-logs/a-connection-pool/app.log \| grep -o 'com\.example\.[A-Za-z.]*' \| sort \| uniq -c` 로 누수 스택의 호출 지점 분포 집계. ② 사고 시점 배포 커밋을 확인하고 `git show <커밋>:modern/api/src/main/java/com/example/assignment/ReportService.java` 로 `:41-71` 비교. ③ 로컬에서 `cd modern/api && ./gradlew bootRun` 후 `time curl -s localhost:8080/api/classes/3/report` 를 1건 · 동시 6건(`&` 로 병렬)으로 실행해 응답 시간과 앱 로그의 누수 감지 · 타임아웃 재현 여부 확인(운영 DB 아님). | **유지** — 누수 감지 134건 전부 스택이 `ReportService.buildClassReport` 이고(R1-1), 점유 시간 15~21초인데 구간 슬로우 쿼리는 1건뿐이라(R1-2, R2-1) 쿼리 밖의 시간이 길다. 반증 ② · ③(배포 버전, `sign()` 실측)은 로그로 확인할 수 없어 코드 · 배포 이력 확인이 남음. |
| H2 | DB · 쿼리 | `submission` 조회(`distribution_id=5`)가 인덱스를 타지 않거나 잠금 대기에 걸려 수 초씩 걸리면서 커넥션 점유 시간이 길어졌고, 그것이 풀 고갈로 이어졌다. | 이상 시작과 같은 초에 9.8초 슬로우 쿼리, `Rows_examined 1,284,310`(`mariadb-slow.log:15`). 쿼리가 `buildClassReport` 가 쓰는 `findByDistributionIdOrderByStudentIdAsc` 모양(`ReportService.java:54`). | ① 구간(14:37~14:52) 슬로우 쿼리가 14:37:52 1건뿐이고, 그 뒤 타임아웃이 계속되는 동안(~14:49) 쿼리 지연 기록이 없다 — 이 경우 쿼리 지연만으로는 12분간 고갈을 설명하지 못한다. ② 같은 쿼리의 `EXPLAIN` 이 `idx_submission_distribution` 을 쓰고 rows 가 작다. ③ 슬로우 로그 `Lock_time` 이 0 에 가깝다(잠금 대기가 아님). | ① `grep -n '^# Time:' incident-logs/a-connection-pool/mariadb-slow.log` 로 UTC 05:37~05:52 항목 수 확인. `sed -n 15,21p incident-logs/a-connection-pool/mariadb-slow.log` 로 `Query_time` · `Lock_time` · `Rows_sent` 확인. ② `docker compose --profile modern up -d` 후 로컬 DB 에서 `EXPLAIN select … from submission where distribution_id=5 order by student_id;`(운영 DB 에 붙지 않음). `db/mariadb/init/01-schema.sql:105-115` 의 인덱스 정의 확인. ③ `Rows_examined 1,284,310` 과 리포트 INFO 의 제출 수(`app.log:1258`, "10 submissions")가 맞지 않는 이유 확인 — 슬로우 로그 `# User@Host` · 스키마 줄로 같은 DB 인지 대조. | **기각(주원인으로서)** — 구간 슬로우 쿼리는 14:37:52 1건뿐(R2-1). `long_query_time` 은 2.04초 이하로 보이는데(2.04초 쿼리가 기록됨) 나머지 133건 리포트의 15~21초 점유를 쿼리 지연으로는 설명하지 못함(리포트 1건 쿼리 5개 × 2초 미만 < 15초). 단 9.8초 · `Rows_examined 1,284,310` 쿼리 자체는 설명되지 않은 채 남아 있음(의심 항목). |
| H3 | 트래픽 | 교사 클라이언트 4개가 `/report` 를 평소(시간당 2~11건)보다 훨씬 자주 호출(재시도 · 새로고침 반복 등)해 동시 요청이 풀 크기를 넘었다. | `/report` 요청이 14:38~14:39 3건/분 → 14:40 10건/분 → 14:41~14:50 10~19건/분, 클라이언트 4개(타임라인 14:38~14:39, 14:40, 14:41~14:50 행; `nginx-access.log:18815` 등). 평소 수준(타임라인 "구간 앞 참고"). | ① `/report` 증가가 첫 5xx(14:40:52) **이후**에 시작된다 — 그러면 증가는 오류에 대한 재시도(결과)이지 원인이 아니다. ② 같은 시간대 다른 날(또는 07~13시)에도 분당 10건 이상 `/report` 가 있었는데 장애가 없었다. ③ 전체 요청 수는 평소와 같다(34~58건/분, 타임라인 14:41~14:50 행) — 총량은 그대로이고 구성만 바뀐 것인지 확인. | ① `awk '/\/api\/classes\/[0-9]+\/report/ {print substr($4,14,5), $1}' incident-logs/a-connection-pool/nginx-access.log \| sort \| uniq -c` 로 분 · 클라이언트별 `/report` 수 집계, 14:37~14:41 사이 증가 시점을 14:40:52 와 비교. ② 같은 명령에서 14:30 이전 행과 비교. ③ 클라이언트별 상태 코드: `awk '$7 ~ /report/ {print $1, $9}' … \| sort \| uniq -c` 로 5xx 를 받은 뒤 바로 다시 요청했는지 확인. | **유지** — `/report` 가 14:38 에 3건/분으로 이날 분당 최대(2건, 12:20)를 넘었고, 첫 5xx(14:40:52) **전에** 이미 14:40 대 9건 · 모두 200(R3-1, R3-2). 증가가 오류보다 앞서므로 재시도 결과라는 반증 ① 이 성립하지 않음. 클라이언트별 의도(사용자 새로고침인지 자동 재시도인지)는 로그로 알 수 없음. |
| H4 | 인프라 | 두 번째 API 인스턴스(`API-2`)가 내려가 있어 모든 요청이 `API-1` 한 대(풀 5개)로만 몰렸고, 평소 두 대에 나뉘던 부하를 한 대가 받았다. | `API-2` 연결 거부 · 첫 502(`nginx-error.log:11`, `nginx-access.log:18970`). 이어 `no live upstreams`(`nginx-error.log:12`). 슬로우 쿼리 · 504 upstream 은 모두 `API-1`(`mariadb-slow.log:15`, `nginx-error.log:6`). | ① 14:42 이전(평소)에도 `API-2` 로 간 요청이 전혀 없다 — 원래 한 대로 운영 중이었다면 "부하가 몰렸다"는 변화가 없다. ② `API-2` 의 연결 거부가 14:37 이전에는 없고 14:42 에 처음 나타난다 — 그러면 `API-2` 다운은 `API-1` 고갈 **이후**의 일(결과 또는 별개 사건)이다. ③ `API-2` 의 앱 로그에서 같은 시각 정상 기동 상태가 확인된다. | ① access 로그에 `$upstream_addr` 가 없으므로 `grep -c 'API-2' incident-logs/a-connection-pool/nginx-error.log` 와 nginx upstream 설정(배포 저장소의 `nginx.conf` `upstream` 블록) 확인. ② `grep -n 'API-2' incident-logs/a-connection-pool/nginx-error.log \| head -3` 로 첫 등장 시각 확인. ③ 현재 수집된 `app.log` 가 어느 호스트 것인지(`API-1` 인지) 확인하고, `API-2` 의 앱 로그 · 컨테이너 재시작 이력(`docker ps -a`/오케스트레이터 이벤트)을 추가로 요청. | **기각(이상 시작 원인으로서)** — `API-2` 는 nginx-error 에 14:42:01 에 처음 나타남(14건, 모두 `Connection refused`, 14:50:52 까지)(R4-1). 이상 시작(14:37:52) · 첫 5xx(14:40:52)보다 늦음. 그 이후 부하를 키웠는지는 **판단 불가**: access 로그에 `$upstream_addr` 가 없어 평소 `API-2` 가 요청을 받았는지 알 수 없음. `$upstream_addr` · `$upstream_response_time` 이 찍힌 access 로그와 `API-2` 의 app.log 가 있으면 판단 가능. |
| H5 | 배포 · 데이터 변경 | 14시대 재배포(배포 7 · 5 · 8)로 리포트 대상 데이터나 실행 경로가 바뀌어 `/report` 한 건의 처리 시간이 길어졌다(또는 14시대에 앱 재기동 · 설정 변경이 있었다). | 배포 7 재배포 2회(`app.log:1129`, `:1182`), 배포 5 재배포 14:38:29(`app.log:1278`) — 슬로우 쿼리 대상도 `distribution_id=5`(`mariadb-slow.log:15`). 배포 8 재배포 14:44:19(`app.log:2929`). | ① 배포 5 재배포(14:38:29)가 슬로우 쿼리(14:37:52) · 첫 누수 감지(14:37:52)보다 **늦다** — 재배포가 이상 시작의 원인일 수는 없다(이미 타임라인상 순서는 이렇게 보이므로 우선 확인 대상). ② `redistribute` 가 `submission` 행을 추가 · 복제하지 않는다(데이터량 불변). ③ `app.log` 구간 앞에 `Started …Application` · `HikariPool-… - Start completed` 같은 재기동 흔적이 없다. | ① 타임라인 14:37:52 · 14:38:29 행 비교, `grep -n 'redistributed distribution' incident-logs/a-connection-pool/app.log` 로 오늘 전체 재배포 시각 목록. ② `modern/api/src/main/java/com/example/assignment/DistributionService.java:50-60` 에서 `redistribute` 가 무엇을 바꾸는지 확인. ③ `grep -nE 'Started .*Application\|Start completed\|Shutdown' incident-logs/a-connection-pool/app.log` 로 재기동 여부, `git log --since=2026-09-15 --until=2026-09-17 -- modern/api` 로 배포 변경 확인. | **기각(재배포)** / **판단 불가(06:43 이전 배포 · 설정 변경)** — 재배포는 08:24~14:44 에 29건, 배포 5 도 09:27 · 09:45 · 13:19 에 문제 없이 있었고 14:38:29 건은 이상 시작보다 늦음(R5-1). app.log 에 재기동 흔적 0건(R5-2). 다만 app.log 가 06:43 부터라 그 전 배포는 보이지 않음 — 배포 이력(CI/CD 로그, 기동 시각이 찍힌 app.log 앞부분)이 있으면 판단 가능. |

### 3.2 가설 사이의 관계(판정 아님)

- 가설들은 서로 배타적이지 않다. 예: H1(긴 점유) 위에 H3(동시 요청 증가)가 겹쳐야 풀 5개가 고갈된다는 식의 **조합**일 수 있다. 검증할 때 "어느 하나가 맞다"가 아니라 각 가설의 반증 조건을 독립적으로 확인한다.
- **선후 관계가 핵심 판별점**이다. 위 확인 방법 중 H3 ①, H4 ②, H5 ① 은 "그 사건이 14:37:52 이전에 시작됐는가"를 묻는다. 이상 시작보다 늦게 시작한 사건은 원인 후보에서 결과 후보로 옮긴다.
- 회복(14:52) 쪽도 설명해야 한다. 어떤 가설이든 "왜 14:52 에 스스로 멈췄는가"(`/report` 요청 감소? `API-2` 복귀? 재기동?)를 함께 설명하지 못하면 불완전하다. 확인: `awk` 로 14:48~14:55 분별 `/report` 수와 `nginx-error.log` 마지막 `API-2` 등장 시각 비교.

### 3.3 로그 확인 실행 기록

모든 명령은 `incident-logs/a-connection-pool/` 에서 실행했다(2026-10-06). 명령 · 출력의 `API-1` · `API-2` · `교사단말A~D` 는 마스킹한 값이라, 다시 실행할 때는 실제 주소를 넣는다. 결과 숫자는 출력 그대로다. `app.log` 원문에는 학생 식별자 · 이메일이 들어 있어 여기에는 숫자만 옮긴다.

**R1-1 (H1) 누수 감지 스택의 호출 지점**

```bash
grep -c 'Connection leak detection' app.log
grep -A12 'Connection leak detection' app.log | grep -o 'com\.example\.[A-Za-z.$]*' | sort | uniq -c
```
```
134
 134 com.example.assignment.ReportController.classReport
 134 com.example.assignment.ReportService$$SpringCGLIB$$
```

**R1-2 (H1) 누수 감지된 커넥션의 점유 시간** — 같은 `Connection@…` 의 감지 줄과 `unleaked` 반환 줄의 시각 차 + 10초(`leak-detection-threshold`)

```bash
grep -c 'unleaked' app.log
grep -E 'Connection leak detection|unleaked' app.log | awk '{t=substr($1,12,12); split(t,a,":"); s=a[1]*3600+a[2]*60+a[3];
  match($0,/Connection@[0-9a-f]+/); c=substr($0,RSTART,RLENGTH);
  if ($0 ~ /leak detection/) st[c]=s; else if (c in st) {printf "%d\n", s-st[c]+10; delete st[c]}}' | sort -n | uniq -c
```
```
134
15s:4 16s:11 17s:23 18s:33 19s:14 20s:33 21s:16      (합 134)
```

**R2-1 (H2) 슬로우 쿼리 시각 · 상세**

```bash
grep -n '^# Time:' mariadb-slow.log
grep -nE 'Query_time|User@Host' mariadb-slow.log
```
```
1:# Time: 260916  0:14:09        (KST 09:14:09)
8:# Time: 260916  2:02:42        (KST 11:02:42)
15:# Time: 260916  5:37:52       (KST 14:37:52)
4:# Query_time: 2.318446  Lock_time: 0.000071  Rows_sent: 7  Rows_examined: 41822
11:# Query_time: 2.041903  Lock_time: 0.912004  Rows_sent: 5  Rows_examined: 5
18:# Query_time: 9.812417  Lock_time: 0.000071  Rows_sent: 4  Rows_examined: 1284310
(세 건 모두 User@Host: app[app] @ [API-1])
```

**R3-1 (H3) 분별 `/report` 요청 수 · 이전 최대**

```bash
awk '$7 ~ /\/api\/classes\/[0-9]+\/report/ {m=substr($4,14,5); if (m>="14:30" && m<="14:55") print m}' nginx-access.log | sort | uniq -c
awk '$7 ~ /\/api\/classes\/[0-9]+\/report/ {print substr($4,14,2)}' nginx-access.log | sort | uniq -c
awk '$7 ~ /\/api\/classes\/[0-9]+\/report/ {m=substr($4,14,5); if (m<"14:37") print m}' nginx-access.log | sort | uniq -c | sort -rn | head -3
```
```
14:38 3 · 14:39 3 · 14:40 10 · 14:41 16 · 14:42 15 · 14:43 19 · 14:44 14 · 14:45 15 · 14:46 19 · 14:47 12 · 14:48 14 · 14:49 16 · 14:50 10 · 14:51 5   (14:30~14:37, 14:52~14:55 은 0)
시간대별: 07시 2 · 08시 5 · 09시 2 · 10시 11 · 11시 7 · 12시 11 · 13시 8 · 14시 179 · 15시 4
14:37 이전 분당 최대: 2 (12:20), 그다음 1 (14:18), 1 (14:17)
```

**R3-2 (H3) 첫 5xx 전후 `/report` · 클라이언트별 상태**

```bash
awk '$7 ~ /\/report/ {t=substr($4,14,8); if (t>="14:40:00" && t<"14:40:52") a++; else if (t>="14:40:52" && t<="14:40:59") b++} END{print a+0, b+0}' nginx-access.log
awk '$7 ~ /\/report/ {t=substr($4,14,8); if (t>="14:37:00" && t<"14:40:52") print $9}' nginx-access.log | sort | uniq -c
awk '$7 ~ /report/ {print $1, $9}' nginx-access.log | sort | uniq -c
```
```
9 1                      (14:40:00~14:40:51 에 9건, 14:40:52~59 에 1건)
15 200                   (14:37:00~14:40:51 의 /report 15건 전부 200)
교사단말A  200:47  500:3  502:14  504:23
교사단말B  200:39  500:1  502:8   504:9
교사단말C  200:38  400:2  502:8   504:8
교사단말D  200:11  500:1  502:2   504:15
```

참고(증상 분포): 500 응답 49건 중 `/report` 는 5건이고 나머지 44건은 `/api/units` 14 · `/api/units/X/items` 19 · `/api/items/N` 7 · `/api/distributions/N` 4 였다. 504 55건은 전부 `/api/classes/N/report` 였다.

**R4-1 (H4) `API-2` 등장**

```bash
grep -c 'API-2' nginx-error.log
grep -c 'API-1' nginx-error.log
grep -n 'API-2' nginx-error.log | head -1; grep -n 'API-2' nginx-error.log | tail -1
grep 'API-2' nginx-error.log | sed -E 's/.*\] [0-9#]+: \*[0-9]+ //; s/, client.*//' | sort | uniq -c
```
```
14
83
11: 2026/09/16 14:42:01 … connect() failed (111: Connection refused) … upstream: "http://API-2:8080/api/classes/2/report"
159: 2026/09/16 14:50:52 … connect() failed (111: Connection refused) … upstream: "http://API-2:8080/api/items/15"
  14 connect() failed (111: Connection refused) while connecting to upstream
```

`app.log` 에는 호스트명 · IP 단서가 없어(`grep -nE 'hostname|10\.20\.|Started|Tomcat started' app.log` 0건) 수집된 app.log 가 `API-1` 의 것인지는 로그로 확정하지 못했다.

**R5-1 (H5) 재배포 목록**

```bash
grep -c 'redistributed distribution' app.log
grep 'redistributed distribution' app.log | grep -o 'distribution [0-9]*' | sort | uniq -c
grep -n 'redistributed distribution 5 ' app.log | cut -c1-40
```
```
29                        (첫 08:24:47 `app.log:190`, 마지막 14:44:19 `app.log:2929`)
배포별: 5 → 4건 · 6 → 8건 · 7 → 8건 · 8 → 9건
배포 5: 4건 — 09:27:05(`:353`) · 09:45:39(`:408`) · 13:19:42(`:995`) · 14:38:29(`:1278`)
```

**R5-2 (H5) 재기동 흔적**

```bash
grep -nE 'Started .*Application|Start completed|Shutdown|Starting .*Application' app.log
```
```
(출력 없음, 0건)
```

### 3.4 판정 요약과 남은 확인

| 가설 | 판정 | 다음 확인(로그 밖) |
| --- | --- | --- |
| H1 애플리케이션 코드 | 유지 | 사고 당시 배포 커밋의 `ReportService.java:41-71`, 로컬에서 `/report` 1건 · 동시 6건 응답 시간 재현 |
| H2 DB · 쿼리 | 기각(주원인으로서) | 9.8초 · 128만 행 쿼리의 정체(같은 DB 인지, `EXPLAIN`) — 의심 항목으로 별도 추적 |
| H3 트래픽 | 유지 | 14:38 부터 `/report` 를 누른 교사 화면에 자동 새로고침 · 재시도가 있는지(프런트 코드) |
| H4 인프라 | 기각(이상 시작 원인으로서) · 이후 영향은 판단 불가 | `$upstream_addr` 포함 access 로그, `API-2` 의 app.log · 재시작 이력 |
| H5 배포 · 설정 변경 | 기각(재배포) · 판단 불가(06:43 이전 배포) | 배포 이력, 06:43 이전 app.log |

남은 두 가설(H1 · H3)은 서로 배타적이지 않다. R1-2(1건당 15~21초 점유)와 R3-1(분당 10~19건)을 함께 놓으면 동시 점유 수가 풀 크기 5 근처까지 올라갈 수 있다. 다만 이 계산은 추정이며, 로그만으로는 확정하지 않는다.

### 3.5 판정

대조 범위: 로그에 찍힌 클래스(`ReportController` · `ReportService`, `app.log:1243`~`:1253` 스택)와 설정 키(`itembank-pool`, `connection-timeout`, `leak-detection-threshold`)를 단서로 `modern/api` 코드 · 설정과 `modern/web` 호출 코드, `db/mariadb/init/01-schema.sql` 을 읽었다. 벤치마크 · 테스트는 실행하지 않았다. 설정값은 모두 파일에서 읽은 값이다.

#### 3.5.1 유지된 가설과 코드 · 설정 대조

| 가설 | 코드 · 설정 판정 | 근거(파일:줄번호 · 인용) |
| --- | --- | --- |
| H1 애플리케이션 코드 | **지지한다** | ① 진입 경로가 로그와 같다. `ReportController.java:20-22` `@GetMapping("/{id}/report")` → `return reportService.buildClassReport(id);` (로그 스택 `app.log:1254` `ReportController.classReport(ReportController.java:22)` 와 줄번호까지 일치). ② 트랜잭션 범위 안에서 서명 루프가 돈다. `ReportService.java:41` `@Transactional`, `:42` `public ClassReport buildClassReport(Integer classId)`, `:71` `String signature = sign(payload.toString());` — DB 조회(`:43`, `:46`, `:54`)가 끝난 뒤에도 메서드가 끝날 때까지 커넥션을 반환하지 않는다. 코드 주석이 스스로 적고 있다: `:69-70` `// TODO: 트랜잭션 밖으로 — … DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` ③ 루프 크기: `:23` `static final int SIGNATURE_ROUNDS = 200_000;`, `:88-91` 매 회 `digest.digest(current)`. ④ 커넥션은 트랜잭션 시작 때 잡힌다 — 로그 스택의 `JpaTransactionManager.doBegin` → `HikariDataSource.getConnection`(`app.log:1243` 다음 줄들). ⑤ 풀 설정: `application.yml:14` `maximum-pool-size: 5`, `:15` `connection-timeout: 3000`, `:18` `leak-detection-threshold: 10000` — 로그의 `total=5`, `timed out after 3000ms`(`app.log:1522`), 10초 넘게 쥔 커넥션만 누수로 찍히는 것(R1-2: 134건 모두 15~21초)과 값이 일치. `application.yml:11-12` 주석: `운영 값과 같게 맞춘다. … 트랜잭션을 오래 쥐는 코드가 있으면 금방 고갈된다.` ⑥ CLAUDE.md 금지 사항(`@Transactional` 안에서 서명 · 해시 반복 금지, 풀 5)과도 정확히 겹치는 패턴이다. |
| H3 트래픽 | **관련 코드를 찾지 못했다** | `/report` 호출 주체는 nginx 로그 referrer 상 `https://lms.example.com/teacher/classes/N/report`(`nginx-error.log:11` 등)인데, 저장소의 `modern/web/src` 에는 `/report` 를 호출하는 코드가 없다(`grep -rln report modern/web/src` 0건). 공용 조회 훅에도 재시도 · 주기 호출이 없다 — `useApiQuery.ts:38-61` 은 `key` 가 바뀔 때 한 번 호출하고 오류면 `status: 'error'` 로 끝난다. `client.ts:21-30` 도 재시도 없음. 따라서 14:38 부터 `/report` 가 늘어난 이유(교사 화면의 자동 새로고침 · 재시도인지, 사람이 반복 클릭했는지)는 이 저장소 코드로 확인할 수 없다. |

#### 3.5.2 최종 판정

**채택: H1 — `ReportService.buildClassReport` 가 `@Transactional` 안에서 SHA-256 200,000회 서명 루프를 돌며 DB 커넥션을 쥐고 있는 코드 결함.**

로그(누수 스택 134/134 가 이 메서드, 1건당 15~21초 점유)와 코드(`ReportService.java:41`, `:71`, `:69-70`)와 설정(`application.yml:14-18`)이 같은 메커니즘을 가리킨다. 풀이 5개뿐이라 이 요청이 몇 건만 겹쳐도 다른 API(`/api/units` · `/api/items` 등 500 응답 44건, R3-2 참고)가 3초 안에 커넥션을 못 얻고 실패한다. 증상인 `Connection is not available` 은 이 결함의 결과다.

**H3 의 위치(기각 아님, 유발 조건)**: `/report` 증가(분당 10~19건)는 H1 결함이 드러나게 만든 **방아쇠**로 본다. 점유 15~21초 × 분당 10~19건이면 동시 점유가 풀 크기 5 근처까지 간다는 산술(추정)이 맞고, 평소(분당 최대 2건)에는 같은 코드로도 고갈이 없었다. 다만 분당 19건은 작은 트래픽이고 전체 요청 수는 평소와 같았으므로(타임라인 14:41~14:50 행), "트래픽이 비정상적으로 많았다"보다 "정상 범위의 동시 요청도 견디지 못하는 코드였다"가 근본 원인으로 맞다고 판단했다. 재발 방지는 H1 수정(서명을 트랜잭션 밖으로)이 우선이고, H3 은 호출 주체 확인을 별도 항목으로 남긴다.

**기각한 가설**

| 가설 | 기각 사유 |
| --- | --- |
| H2 DB · 쿼리 | 구간 슬로우 쿼리는 14:37:52 1건뿐(R2-1)이라 12분간 134건의 15~21초 점유를 설명하지 못한다. 코드상 리포트 쿼리는 `SubmissionRepository.java:8` `findByDistributionIdOrderByStudentIdAsc` 이고, 저장소 스키마에는 `01-schema.sql:112` `KEY idx_submission_distribution (distribution_id)` 가 있다. 첫 누수 건(14:37:42 획득 추정)은 9.8초 쿼리와 겹쳐 점유가 더 길었을 수 있으나 그 1건에 한정된다. 남은 의심: `Rows_examined 1,284,310`(`mariadb-slow.log:18`) — 운영 스키마에 같은 인덱스가 있는지는 저장소로 확인 불가. |
| H4 인프라 | `API-2` 오류가 14:42:01 에 처음 나타나 이상 시작(14:37:52) · 첫 5xx(14:40:52)보다 늦다(R4-1). 평소 `API-2` 가 요청을 받았는지는 `$upstream_addr` 가 없어 판단 불가로 남는다. |
| H5 배포 · 설정 변경 | 재배포 29건이 하루 내내 있었고(배포 5 도 3회 무사), 14:38:29 건은 이상 시작보다 늦다(R5-1). 코드상 재배포는 `DistributionService.java:55` `distribution.markRedistributed(LocalDateTime.now(clock));` → `Distribution.java:51-52` `distributedAt` · `redistributed` 두 필드만 바꾸고 `submission` 은 건드리지 않아 리포트 처리량을 늘리지 않는다. app.log 에 재기동 흔적 0건(R5-2). 06:43 이전 배포는 로그 밖이다. |

**확신 수준: 중간**

- 높일 근거: 로그 스택 · 코드 경로 · 설정값 · 코드 주석이 같은 결함을 가리키고, 다른 호출 지점에서 누수가 감지된 적이 없다(134/134).
- 높음으로 올리지 못한 이유:
  1. `sign()` 한 번의 실제 소요 시간을 재지 않았다(이번 단계에서 실행 금지). 15~21초 점유 중 얼마가 서명 루프인지 수치로 보이지 않았다.
  2. 사고 당시 운영에 배포된 버전이 이 저장소 코드와 같은지 확인하지 않았다. 저장소에서 `ReportService.java` · `application.yml` 은 초기 커밋(`1260542`) 이후 바뀌지 않았지만, 이는 운영 배포 버전의 증거가 아니다.
  3. 방아쇠(H3)인 `/report` 증가의 원인과 9.8초 · 128만 행 쿼리가 설명되지 않은 채 남아 있다.
- 높음으로 올리려면: 사고 시점 배포 커밋 확인, 로컬(운영 DB 아님)에서 `/report` 단건 · 동시 6건 응답 시간 측정, 교사 화면(`lms.example.com/teacher/classes/N/report`) 쪽 호출 코드 확인.

## 4. 원인

> 서버는 `API-1`(슬로우 쿼리 · 504 의 upstream), `API-2`(14:42 연결 거부)로, 교사 클라이언트는 `교사단말A~D` 로 적는다(문서 끝 마스킹 참고).

### 4.1 직접 원인

- **C1.** `GET /api/classes/{id}/report` 는 `ReportService.buildClassReport` 로 들어가고, 이 메서드는 `@Transactional` 이라 시작할 때 DB 커넥션을 빌린다.
- **C2.** 메서드는 조회를 마친 뒤에도 같은 트랜잭션 안에서 SHA-256 200,000회 서명 루프를 돈다. 커넥션은 메서드가 끝날 때까지 반환되지 않는다.
- **C3.** 그 결과 리포트 요청 1건이 커넥션을 15~21초 쥐었다(누수 감지 134건 전부 이 메서드).
- **C4.** 14:38 부터 리포트 요청이 분당 3건 → 14:40 10건 → 14:41~14:50 10~19건으로 늘어, 동시에 커넥션을 쥔 리포트 요청이 풀 크기(5)에 닿았다.
- **C5.** 풀이 비자 다른 API 요청이 3초 대기 후 실패했다(첫 실패 14:40:52 `active=5, idle=0, waiting=4`). 500 49건 중 44건은 리포트가 아닌 문항 · 단원 · 배포 API 였다.
- **C6.** 리포트 요청 자체는 nginx 에서 응답 대기 시간을 넘겨 504(55건)가 됐고, nginx 가 `API-1` 을 일시 제외하면서 `API-2` 연결 거부와 겹쳐 "no live upstreams" 502(82건)로 번졌다.
- **C7.** 14:50 이후 리포트 요청이 분당 10 → 5 → 0건으로 줄자 14:51:47 마지막 5xx 를 끝으로 스스로 회복했다.

### 4.2 배경 원인(C1~C7 을 가능하게 한 조건)

- **B1. 풀이 작고 대기 시간이 짧다.** `maximum-pool-size: 5`, `connection-timeout: 3000`. 오래 쥐는 요청 몇 건으로 전체 API 가 막힌다. 설정 주석이 이 위험을 이미 적고 있다.
- **B2. 알려진 결함이 남아 있었다.** 코드에 `TODO: 트랜잭션 밖으로` 주석이 있었고, CLAUDE.md 금지 사항("`@Transactional` 안에서 서명 · 해시 반복 금지")과 같은 패턴인데도 걸러지지 않았다.
- **B3. 무거운 API 와 가벼운 API 가 같은 풀을 쓴다.** 리포트 한 종류가 풀을 다 쓰면 문항 조회까지 함께 실패한다.
- **B4. 경보가 없었다.** 누수 감지 WARN 이 14:37:52 부터 찍혔지만(첫 5xx 3분 전) 경보로 이어지지 않았다.

## 5. 근거 로그 줄

| 문장 | 파일:줄번호 | 발췌 |
| --- | --- | --- |
| C1 | `app.log:1254` · `app.log:1250` · `app.log:1245` | `ReportController.classReport(ReportController.java:22)` · `JpaTransactionManager.doBegin` · `HikariDataSource.getConnection` |
| C1 | `ReportController.java:22` · `ReportService.java:41` | `return reportService.buildClassReport(id);` · `@Transactional` |
| C2 | `ReportService.java:71` · `:23` · `:69-70` | `String signature = sign(payload.toString());` · `SIGNATURE_ROUNDS = 200_000` · `// TODO: 트랜잭션 밖으로` |
| C3 | `app.log:1243` → `app.log:1259` | 14:37:52 `Connection leak detection triggered for …Connection@6e8f14d3` → 14:38:01 `Previously reported leaked connection …@6e8f14d3` |
| C3 | `app.log:1243` ~ `app.log:5251` · `app.log:1253` (집계는 3.3 R1-1 · R1-2) | 첫 · 마지막 누수 감지 사이 134건, 스택 `ReportService$$SpringCGLIB$$0.buildClassReport` 134/134, 점유 15~21초 |
| C4 | `nginx-access.log:18801` | 14:38:01 `"GET /api/classes/3/report HTTP/1.1" 200` (이후 분별 수는 3.3 R3-1) |
| C5 | `app.log:1522` | `itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| C5 | `app.log:1523` · `nginx-access.log:18918` | `unhandled exception on /api/units/M6-2/items` · `"GET /api/units/M6-2/items HTTP/1.1" 500` |
| C6 | `nginx-error.log:6` · `nginx-access.log:18933` | 14:41:06 `upstream timed out (110: Connection timed out) while reading response header` · `/report … 504` |
| C6 | `nginx-error.log:10` · `:11` · `:12` | `upstream server temporarily disabled` · `connect() failed (111: Connection refused)`(API-2) · `no live upstreams` |
| C7 | `app.log:4980` · `app.log:5251` · `nginx-error.log:168` | 14:49:51 마지막 타임아웃 · 14:51:37 마지막 누수 감지 · 14:51:47 마지막 upstream 오류 |
| C7 | `nginx-access.log:19393` | 14:52:00 이후 5xx 0건 |
| B1 | `application.yml:14-15` · `:11-12` | `maximum-pool-size: 5` · `connection-timeout: 3000` · `풀이 작고 대기 시간이 짧아, 트랜잭션을 오래 쥐는 코드가 있으면 금방 고갈된다.` |
| B2 | `ReportService.java:69-70` | `DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` |
| B4 | `app.log:1243` vs `app.log:1522` | 첫 누수 감지 14:37:52, 첫 타임아웃 14:40:52 |

## 6. 수정안(지금 적용)

| # | 파일 | 무엇을 | 어떻게 |
| --- | --- | --- | --- |
| F1 | `modern/api/src/main/java/com/example/assignment/ReportService.java:41-82` | 조회와 서명을 분리 | 학급 · 배포 · 제출 조회와 집계(`:43-67`)만 트랜잭션 안에서 하고, 결과(학급 id · 이름 · 건수 · 평균 · payload 문자열)를 값 객체로 돌려받은 뒤 **트랜잭션이 끝난 다음** `sign()`(`:71`)을 호출한다. 같은 클래스 안 자기 호출은 `@Transactional` 프록시를 거치지 않으므로, 조회 부분은 같은 패키지의 별도 빈(예: `ReportQueryService`)으로 빼거나 `TransactionTemplate` 으로 감싼다. `buildClassReport` 자체에서는 `@Transactional` 을 뗀다. |
| F2 | 같은 파일 | 조회 트랜잭션을 읽기 전용으로 | 분리한 조회 메서드에 `@Transactional(readOnly = true)`(CLAUDE.md 계층 규칙). |
| F3 | `modern/api/src/test/java/com/example/assignment/` | 회귀 테스트 | ① 서비스 단위 테스트: 서명 시점에 `TransactionSynchronizationManager.isActualTransactionActive()` 가 `false` 인지 확인. ② 기존 리포트 응답 값(건수 · 평균 · 서명)이 바뀌지 않는지 확인. `@DisplayName` 한국어. |

- 수정 후 `cd modern/api && ./gradlew test` 와 `characterization` 의 `npm test`(리포트 응답 모양 보존)를 통과해야 완료다.
- **하지 않는 것**: `application.yml` 의 풀 크기 · 타임아웃 변경(CLAUDE.md 금지, 근본 원인도 아님), 서명 반복 횟수 변경(외부 집계 시스템 요구값, `ReportService.java:22`).

## 7. 재발 방지(구조적 장치)

| # | 장치 | 막는 것 |
| --- | --- | --- |
| P1 | **리뷰 체크리스트에 항목 추가**: `approval-checklist.md` 에 "`@Transactional` 메서드 안에 외부 호출 · 해시 · 서명 · `sleep` · 대용량 루프가 없는가"를 넣고, `verify` · `convention-check` Skill 이 이 항목을 검사하게 한다. | B2: 규칙이 문서에만 있고 리뷰에서 걸러지지 않는 문제 |
| P2 | **`TODO` 를 결함으로 등록**: 커넥션 · 트랜잭션 관련 `TODO` 는 코드 주석에 두지 않고 이슈로 등록해 우선순위를 매긴다. | B2: 알려진 위험이 방치되는 문제 |
| P3 | **아키텍처 테스트**: "`MessageDigest` · HTTP 클라이언트를 쓰는 메서드에 `@Transactional` 금지"를 테스트로 강제한다. ArchUnit 을 쓰면 의존성 추가가 필요하므로 별도 승인 후 진행(대안: 리플렉션으로 같은 규칙을 검사하는 테스트 직접 작성). | B2 의 재발을 사람 리뷰 없이 차단 |
| P4 | **무거운 API 격리(벌크헤드)**: 리포트처럼 오래 걸리는 API 는 동시 실행 수를 풀 크기보다 작게(예: 2) 제한하고 초과 요청은 즉시 429/503 으로 돌려준다. | B3: 한 API 가 풀 전체를 잡아 다른 API 까지 실패하는 문제 |
| P5 | **동시성 회귀 테스트**: 리포트 API 를 풀 크기 + 1 건 동시에 호출해 다른 API 가 3초 안에 응답하는지 확인하는 테스트를 배포 전 검증에 둔다. | B1 · B3 조건에서 같은 유형 결함이 다시 들어오는 것 |

## 8. 모니터링 항목

현재 `build.gradle` 에 Actuator · Micrometer 가 없어 Hikari 지표를 내보내려면 의존성 추가 승인이 필요하다(대안: 이미 찍히는 로그 줄 수를 로그 수집기에서 세어 경보). "이번 경보 시각"은 그 지표가 이번 타임라인에 있었다면 처음 울렸을 시각이다.

| 지표 | 임계값(제안) | 이번 경보 시각 | 근거 |
| --- | --- | --- | --- |
| Hikari 누수 감지 WARN 수 | 5분에 1건 이상 | **14:37:52** | `app.log:1243`. 평소 0건 |
| 리포트 API 응답 시간 | 1건이라도 10초 초과 | 14:38:01 (추정) | `app.log:1243` 누수 감지(획득 후 10초) → `app.log:1259`/`nginx-access.log:18801` 응답. `$request_time` 미기록이라 추정 |
| 리포트 API 요청 수 | 분당 3건 이상(평소 분당 최대 2건) | 14:38 | 3.3 R3-1 (14:38 에 3건) |
| 누수 감지된 커넥션 수(동시) | 3개 이상(풀의 60%) | 14:39 대 | 타임라인 14:38~14:39 행(`app.log:1261`~`:1331`, 3개) |
| 커넥션 대기(`pending`) · 획득 타임아웃 | 대기 1 이상 또는 타임아웃 1건 이상 | 14:40:52 | `app.log:1522` (`waiting=4`) |
| API 5xx 비율 | 1분 5% 초과 | 14:41 | 14:40 은 1/42(2.4%), 14:41 은 9/42(21%) — 1.3절 표 |
| nginx upstream 오류 | 1분 1건 이상 | 14:41:06 | `nginx-error.log:6` |
| 슬로우 쿼리 | 5초 초과 1건 이상 | 14:37:52 | `mariadb-slow.log:15`(9.8초). 평소 2건은 2.0~2.3초라 울리지 않음 |

- 누수 감지 WARN 경보를 걸었다면 **첫 5xx(14:40:52)보다 3분 먼저** 알 수 있었다. 이 경보를 1순위(호출), 5xx 비율을 2순위로 둔다.

## 9. 확인하지 못한 것

| 판단하지 못한 것 | 이유 | 다음을 위해 남길 것 |
| --- | --- | --- |
| 서명 루프가 15~21초 중 몇 초인지 | 이번 RCA 에서 실행 · 측정하지 않음 | F3 테스트와 함께 로컬에서 `sign()` 소요 시간 측정 기록 |
| 운영 배포 버전이 이 저장소 코드와 같은지 | 배포 이력 없음, app.log 는 06:43 부터 | 기동 시 버전 · 커밋 해시를 INFO 로 남기기, 배포 이력 보관 |
| 리포트 요청이 14:38 부터 늘어난 이유 | 호출하는 교사 화면 코드가 이 저장소에 없음, 클라이언트 로그 없음 | 프런트 요청 로그(자동 새로고침 · 재시도 여부), 요청에 클라이언트 요청 ID 헤더 |
| 9.8초 · `Rows_examined 1,284,310` 쿼리(`mariadb-slow.log:15-21`)의 정체 | 운영 스키마 · 실행 계획 없음, 리포트 INFO 의 제출 10건과 맞지 않음 | 슬로우 로그에 실행 계획 기록(`log_slow_verbosity`), 운영 스키마 인덱스 목록 |
| `API-2` 가 평소 요청을 받았는지, 14:42 에 왜 연결을 거부했는지 | access 로그에 `$upstream_addr` 없음, `API-2` 의 app.log 없음 | nginx `log_format` 에 `$request_time $upstream_addr $upstream_response_time $upstream_status`, 인스턴스별 app.log |
| 수집된 app.log 가 어느 인스턴스 것인지 | 로그 패턴에 호스트 정보 없음 | 로그 패턴에 호스트명 · 인스턴스 ID |
| 504 를 만든 nginx 응답 대기 시간 값 | nginx 설정이 저장소에 없음 | nginx 설정 파일을 장애 자료와 함께 보관 |
| 풀 사용량의 실제 추이(14:37~14:40) | Hikari 지표 없음, 타임아웃 메시지에서만 보임 | 1분 단위 `active` · `idle` · `pending` 기록(8절) |

---

마스킹 적용: IP 40건 · 이메일 0건 · 학생 식별자 0건 · 토큰 0건 · 비밀번호 · 접속 문자열 0건
