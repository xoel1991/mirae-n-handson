# RCA: b-deploy-5xx

> **작성 범위(중간본)**: 이번 판은 1절(수집 범위), 2절(타임라인), 3절(가설과 검증) 중 **가설 표까지만** 담는다. 확인 방법의 명령 실행 · 반증 판정 · 코드 대조(4단계)와 요약 · 원인 · 수정안 · 모니터링 · 확인하지 못한 것은 아직 쓰지 않았다.

## 1. 수집 범위

대상: `incident-logs/b-deploy-5xx/` (모든 파일을 `wc` · `head` · `tail` · `grep` · `awk` 집계로만 확인. 파일을 통째로 읽지 않음. `deploy-history.md` 는 39줄이라 전체를 읽음)

### 1.1 파일별 기록 기간 · 형식 · 타임존

| 파일 | 줄 수 | 기록 기간(원문 표기) | 기록 기간(KST) | 시각 형식 | 타임존 |
| --- | ---: | --- | --- | --- | --- |
| `app.log` | 2,938 (시각이 찍힌 줄 1,275 · 스택 트레이스 등 1,663) | `2026-09-17T09:00:04.878+09:00` ~ `2026-09-17T14:39:57.905+09:00` | 09:00:04 ~ 14:39:57 | ISO 8601, 밀리초 | `+09:00` 명시 (KST) |
| `nginx-access.log` | 20,365 | `[17/Sep/2026:09:00:00 +0900]` ~ `[17/Sep/2026:14:39:58 +0900]` | 09:00:00 ~ 14:39:58 | nginx combined (`$request_time` · `$upstream_addr` 없음) | `+0900` 명시 (KST) |
| `deploy-history.md` | 39 | v1.3.9 `2026-09-01 18:02:47` ~ v1.4.2 `2026-09-17 13:42:31` | 같음 | `YYYY-MM-DD HH:MM:SS` | 문서에 "시각은 모두 KST" 명시(`deploy-history.md:3`) |

- `app.log` 는 한 프로세스(PID 1) 로그다. 어느 호스트 것인지는 로그에 없다. 배포 이력은 배포 대상을 `API-1`, 대기 호스트를 `API-2`(평소 정지)로 적고 있다(`deploy-history.md:3`). `API-1` · `API-2` 는 마스킹한 값이며 다시 실행할 때는 실제 주소를 넣는다.
- `nginx-access.log` 에 upstream 필드가 없어 요청이 어느 서버로 갔는지 access 로그만으로는 알 수 없다. nginx error 로그는 수집되지 않았다.

### 1.2 KST 변환 규칙

- 세 파일 모두 KST. 변환 없음.
- 교차 확인: 앱 종료 · 기동(`app.log:1240` 13:41:48 ~ `app.log:1264` 13:42:00)과 access 로그 502 구간(`nginx-access.log:16865` 13:41:51 ~ `:16872` 13:41:59), 배포 이력의 v1.4.2 배포 시작 · 완료(`deploy-history.md:7`, 13:40:05 ~ 13:42:31)가 같은 시각대에 맞물린다. 9시간 어긋남이 없다.

### 1.3 평소 수준과 이상 건수

**시간대별 건수(전체 로그)**

| 시(KST) | access 전체 | access 5xx | app WARN | app ERROR | 재배포 POST 200 | 재배포 POST 409 | 재배포 POST 500 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 09 | 3,625 | 3 | 4 | 3 | 10 | 4 | 3 |
| 10 | 3,621 | 1 | 8 | 1 | 12 | 8 | 0 |
| 11 | 3,762 | 3 | 8 | 3 | 10 | 8 | 3 |
| 12 | 3,215 | 0 | 9 | 0 | 20 | 9 | 0 |
| 13 | 3,678 | 22 | 3 | 14 | 18 | 3 | 14 |
| 14 (~14:39) | 2,464 | 25 | 3 | 25 | 2 | 3 | 25 |

- 상태 코드 전체: 200 19,278 · 400 29 · 404 969 · 409 35 · 500 46 · 502 8.
- app WARN 35건은 전부 `state conflict: 마감된 과제는 재배포할 수 없습니다`(재배포 409 와 같은 수). app ERROR 46건은 전부 `unhandled exception on /api/distributions/…` 이고, 원인 예외는 46건 모두 `Entity com.example.assignment.Assignment with identifier value 9 does not exist` 이다.

**평소 수준(09:00 ~ 13:41:50, 배포 전)**

| 파일 | 집계 대상 | 평소 수준 |
| --- | --- | --- |
| `nginx-access.log` | 5xx | 4시간 41분 동안 7건(모두 500). 7건 모두 배포 41 에 대한 요청(`POST /api/distributions/41/redistribute` 6 · `GET /api/distributions/41` 1), 모두 같은 클라이언트 `교사단말A`(`nginx-access.log:3150` 외). `교사단말A` · `교사단말B` 는 마스킹한 값이며 다시 실행할 때는 실제 주소를 넣는다 |
| `app.log` | ERROR | 7건(09:52 3 · 10:31 1 · 11:17~11:18 3). 스택이 모두 `DistributionService.loadOrThrow`(`DistributionService.java:65`) |
| `app.log` | WARN | 시간당 4~9건(재배포 409) |
| `nginx-access.log` | 배포 42 · 43 재배포 | 18건, 전부 200 (모두 `교사단말A`) |

**분석 구간 5분 단위(13:30 ~ 14:39)**

| 5분(KST) | access 전체 | access 5xx | 재배포 POST 200 | 재배포 POST 500 | 재배포 POST 409 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 13:30 | 313 | 0 | 0 | 0 | 0 |
| 13:35 | 350 | 0 | 0 | 0 | 0 |
| 13:40 | 331 | 8 (502) | 2 | 0 | 0 |
| 13:45 | 294 | 3 | 0 | 3 | 0 |
| 13:50 | 279 | 6 | 2 | 6 | 0 |
| 13:55 | 270 | 5 | 1 | 5 | 1 |
| 14:00 | 315 | 6 | 0 | 6 | 0 |
| 14:05 | 298 | 1 | 0 | 1 | 0 |
| 14:10 | 313 | 3 | 0 | 3 | 0 |
| 14:15 | 352 | 3 | 0 | 3 | 0 |
| 14:20 | 316 | 3 | 2 | 3 | 1 |
| 14:25 | 289 | 3 | 0 | 3 | 0 |
| 14:30 | 310 | 6 | 0 | 6 | 1 |
| 14:35 | 271 | 0 | 0 | 0 | 1 |

- 앱 재기동(13:42:00) 이후 app ERROR 39건 · access 500 39건. 39건 모두 `POST /api/distributions/42|43/redistribute`, 모두 `교사단말A`.
- 같은 구간 학급 2 · 3 재배포 성공 INFO 6건(학급 2 2건 · 학급 3 4건), 학급 1 재배포 INFO 39건(각각 직후 ERROR).
- 재배포 POST 200 응답 크기: 배포 전 170~193바이트, 배포 후 640~910바이트(6건).

### 1.4 제안 구간

| 구분 | 시각(KST) | 근거 |
| --- | --- | --- |
| 이상 시작 | **13:41:51** | 첫 502(`nginx-access.log:16865`). 앱 graceful shutdown 시작 13:41:48(`app.log:1240`) |
| 오류 본격화 | **13:46:38** | 재기동 후 첫 500 · 첫 ERROR(`nginx-access.log:17155`, `app.log:1275`). 이후 14:34:45 까지 39건 |
| 이상 끝 | **로그 안에서 확인 불가** | 마지막 500 은 14:34:45(`nginx-access.log:20084`, `app.log:2887`). 그 뒤 로그 끝(14:39:58)까지 5xx 0건이지만, 이 5분 동안 학급 1 재배포 요청 자체가 없어 회복인지 요청 중단인지 가를 수 없다. 재기동 · 롤백 흔적(`Starting ItemBankApplication`)도 13:41:55 1건뿐(`app.log:1245`) |

### 1.5 앞뒤 여유

- **앞쪽 11분: 13:30 부터.** 13:30~13:41 은 5xx 0건으로 배포 직전 상태 확인용. 그보다 앞의 배포 41 오류 7건(09:52 ~ 11:18)과 학급 1 리포트(마지막 13:24:45)는 구간 밖이지만 비교용으로 타임라인 앞 참고 항목으로 둔다.
- **뒤쪽: 로그 끝 14:39:58 까지.** 이상 끝이 로그 안에 없어 더 붙일 여유가 없다.
- 결과 분석 구간: **2026-09-17 13:30:00 ~ 14:39:58 KST** (+ 09:00 ~ 13:30 참고 항목).

> 원인 추측은 이 절에 쓰지 않음. 해석은 3절에서 함.

## 2. 타임라인

대상: 2026-09-17 13:30:00 ~ 14:39:58 KST. 해석은 쓰지 않음. 학생 식별자는 `<STU>` 로 바꿈.

**구간 앞 참고(평소 수준 비교용)**

| 시각(KST) | 출처(파일:줄번호) | 사건 요약 | 원문 발췌(80자 이내) |
| --- | --- | --- | --- |
| 09:01:32 | `app.log:6` | 학급 1 리포트 생성. 13:24:45 까지 17건, 모두 같은 수치(마지막 `app.log:1191`) | `built class report for class 1: 3 distributions, 10 submissions` |
| 09:15:53 | `app.log:59` | 배포 43(학급 1) 재배포 성공. 배포 42 · 43 재배포 성공은 13:41:20 까지 18건 | `redistributed distribution 43 (assignment 6, class 1) reason=<STU> 결석으로 재배포` |
| 09:52:17 | `nginx-access.log:3150` · `app.log:194` | 배포 41 재배포 500, `교사단말A`. 09:52:41 까지 3건(`nginx-access.log:3174`) | `"POST /api/distributions/41/redistribute HTTP/1.1" 500 176` |
| 09:52:17 | `app.log:195` | 원인 예외: 과제 9 없음 | `JpaObjectRetrievalFailureException: Entity …Assignment with identifier value 9 does not…` |
| 09:52:17 | `app.log:205` · `app.log:214` | 스택: `loadOrThrow` ← `redistribute` | `at com.example.assignment.DistributionService.loadOrThrow(DistributionService.java:65)` |
| 10:31:05 | `nginx-access.log:5563` · `app.log:451` · `app.log:463` | 배포 41 단건 조회 500. 스택 `getDistribution`(`DistributionService.java:32`) | `"GET /api/distributions/41 HTTP/1.1" 500 176` |
| 11:17:40 | `nginx-access.log:8442` · `app.log:655` | 배포 41 재배포 500. 11:18:02 까지 3건(`nginx-access.log:8468`, `app.log:733`) | `"POST /api/distributions/41/redistribute HTTP/1.1" 500 176` |
| 13:24:45 | `nginx-access.log:15713` · `app.log:1191` | 이날 마지막 학급 1 리포트 요청(200), `교사단말B` | `"GET /api/classes/1/report HTTP/1.1" 200 260` |

**분석 구간**

| 시각(KST) | 출처(파일:줄번호) | 사건 요약 | 원문 발췌(80자 이내) |
| --- | --- | --- | --- |
| 13:38 | `deploy-history.md:21` | v1.4.2 배포 승인 | `승인: <EMAIL> (13:38)` |
| 13:40:05 | `deploy-history.md:7` | v1.4.2 배포 시작(배포 이력 기록). 변경 파일 `DistributionController.java` 1개 | `\| v1.4.2 \| 2026-09-17 13:40:05 \| 2026-09-17 13:42:31 \|` |
| 13:40:05 | `deploy-history.md:15` · `:16` | v1.4.2 변경 내용: 재배포 응답에 `history[]` 추가, 컨트롤러에서 `listByClass(classId)` 호출 추가 | `` `DistributionController.redistribute` 에서 재배포 후 `DistributionService.listByClass… `` |
| 13:40:05 | `deploy-history.md:18` | 배포 방식: 단일 호스트 재기동(무중단 아님) | `배포 방식: 단일 호스트 재기동(무중단 아님). 예상 중단 10초 안팎.` |
| 13:41:20 | `app.log:1238` | 배포 전 마지막 학급 1 재배포 성공(배포 43) | `redistributed distribution 43 (assignment 6, class 1) reason=<STU> 요청 — 제출…` |
| 13:41:48 | `app.log:1240` | 앱 graceful shutdown 시작 | `Commencing graceful shutdown. Waiting for active requests to complete` |
| 13:41:50 | `app.log:1241` · `nginx-access.log:16864` | graceful shutdown 완료. 같은 초 access 마지막 200 | `Graceful shutdown complete` |
| 13:41:51 | `nginx-access.log:16865` | **첫 502.** 13:41:59 까지 8건(`nginx-access.log:16872`), 학생 화면 GET 요청 | `"GET /api/units/M5-2/items?student=<STU> HTTP/1.1" 502 157` |
| 13:41:55 | `app.log:1245` | v1.4.2 기동 시작 | `Starting ItemBankApplication v1.4.2 using Java 21.0.4 with PID 1` |
| 13:42:00 | `app.log:1264` | 기동 완료 | `Started ItemBankApplication in 4.921 seconds (process running for 5.488)` |
| 13:42:10 | `nginx-access.log:16884` · `app.log:1266` | 배포 후 첫 재배포 성공(배포 7, 학급 3), `교사단말B`. 응답 802바이트 | `"POST /api/distributions/7/redistribute HTTP/1.1" 200 802` |
| 13:42:31 | `deploy-history.md:7` | v1.4.2 배포 완료(배포 이력 기록) | `\| v1.4.2 \| 2026-09-17 13:40:05 \| 2026-09-17 13:42:31 \|` |
| 13:46:38 | `app.log:1274` | 배포 42(학급 1) 재배포 INFO | `redistributed distribution 42 (assignment 4, class 1) reason=보강 수업 후 재배포` |
| 13:46:38 | `app.log:1275` · `nginx-access.log:17155` | **재기동 후 첫 ERROR · 첫 500**(INFO 6ms 뒤), `교사단말A` | `unhandled exception on /api/distributions/42/redistribute` |
| 13:46:38 | `app.log:1276` | 원인 예외: 과제 9 없음(배포 전 7건과 같은 문구) | `JpaObjectRetrievalFailureException: Entity …Assignment with identifier value 9 does not…` |
| 13:46:38 | `app.log:1286` · `app.log:1294` | 스택: `listByClass`(`DistributionService.java:38`) ← `DistributionController.redistribute`(`:40`) | `at com.example.assignment.DistributionService.listByClass(DistributionService.java:38)` |
| 13:46:58 · 13:47:08 | `nginx-access.log:17170` · `:17181` | 같은 배포 42 재배포 다시 500 2건, `교사단말A` | `"POST /api/distributions/42/redistribute HTTP/1.1" 500 176` |
| 13:50:00 | `nginx-access.log:17353` | `교사단말A` 의 배포 8(학급 2, `app.log:28`) 재배포 200 | `"POST /api/distributions/8/redistribute HTTP/1.1" 200 908` |
| 13:50:12 ~ 14:34:45 | `nginx-access.log:17365` ~ `:20084` · `app.log:1396` ~ `:2887` | 배포 42 · 43 재배포가 2~4건씩 묶여 반복, 전부 500. 각 ERROR 직전에 같은 배포의 `redistributed` INFO | `"POST /api/distributions/43/redistribute HTTP/1.1" 500 176` |
| 14:34:45 | `app.log:2886` · `app.log:2887` · `nginx-access.log:20084` | 마지막 재배포 INFO · ERROR · 500 | `unhandled exception on /api/distributions/43/redistribute` |
| 14:39:58 | `nginx-access.log:20365` | 로그 끝. 14:34:46 이후 5xx 0건, 학급 1 재배포 요청 0건 | `"GET /api/units HTTP/1.1" 200 613` |

## 3. 가설과 검증

> 이 절은 가설만 세운다. 어느 가설이 맞는지는 아직 판정하지 않는다(확인 방법 실행 · 코드 대조는 다음 판에서).

**증상과 원인을 나눈다.** 가장 많이 나온 `JpaObjectRetrievalFailureException: Entity com.example.assignment.Assignment with identifier value 9 does not exist`(ERROR 46건 전부, `app.log:195` 외)는 **증상**이다. "과제 9 를 찾다가 실패했다"는 사실만 말해 주고, *왜 배포 뒤 학급 1 재배포 전부가* 이 예외를 만나게 됐는지는 말해 주지 않는다. 판별점은 두 가지다.

- **선후 관계**: 같은 예외가 배포 **전**(09:52, `app.log:195`)에도 7건 있었다. 예외 자체는 배포보다 먼저 존재했으므로, 배포가 바꾼 것은 "이 예외를 만나는 요청의 범위"다(배포 전: 배포 41 요청만 · 배포 후: 학급 1 재배포 전부).
- **회복 시점**: 로그 안에서 회복이 관찰되지 않는다(1.4). 가설은 "언제 끝났는가"보다 "배포 후 왜 학급 1 만 실패하고 학급 2 · 3 은 성공하는가"를 설명해야 한다.

### 3.1 가설 목록

| # | 계층 | 가설 한 문장 | 지지 근거(파일:줄번호) | 반증 조건(이것이 확인되면 이 가설은 틀림) | 확인 방법 |
| --- | --- | --- | --- | --- | --- |
| H1 | 배포 · 코드 | v1.4.2 가 `DistributionController.redistribute` 에 추가한 `listByClass(classId)` 호출이 학급의 배포 전체를 불러오면서, 학급 1 에 속한 "과제가 없는 배포"(H2)까지 읽어 예외가 나고, 그래서 학급 1 재배포가 매번 500 이 된다. | 변경 내용이 정확히 이 호출 추가(`deploy-history.md:15` · `:16`). 배포 후 ERROR 39건 스택이 모두 `listByClass`(`DistributionService.java:38`) ← `DistributionController.redistribute`(`:40`)(`app.log:1286` · `:1294`). 같은 배포 42 · 43 재배포가 배포 전 18건 전부 200 → 배포 후 39건 전부 500(1.3). 배포 후 실패는 학급 1 만, 학급 2 · 3 재배포는 성공(`app.log:1266`, `nginx-access.log:17353`). 응답 크기 170~193 → 640~910바이트로 `history[]` 추가와 맞음(1.3). | ① 배포 후 500 중 스택에 `listByClass` 가 **없는** 건이 있다. ② 배포 후 학급 2 · 3 재배포에서도 같은 예외가 난다. ③ 배포 **전**에도 배포 42 · 43 재배포가 같은 예외로 실패한 기록이 있다. ④ 사고 시점 배포된 코드에 `listByClass` 호출이 없다. | ① `awk '/^20[^ ]+ +ERROR/ && NR>1264{n=NR} n && /DistributionService\.[a-zA-Z]+\(/{match($0,/DistributionService\.[a-zA-Z]+/); print substr($0,RSTART,RLENGTH); n=0}' incident-logs/b-deploy-5xx/app.log \| sort \| uniq -c` ② `awk 'NR>1264 && /redistributed distribution/' incident-logs/b-deploy-5xx/app.log \| grep -oE 'class [0-9]+' \| sort \| uniq -c` 와 ERROR 의 URL 대조 ③ `awk '$7 ~ /distributions\/4[23]\/redistribute/ && NR<16865{print $9}' incident-logs/b-deploy-5xx/nginx-access.log \| sort \| uniq -c` ④ v1.4.2 커밋에서 `git show <커밋>:modern/api/src/main/java/com/example/assignment/DistributionController.java` 로 `:40` 부근 확인, `DistributionService.java:38` 의 조회 방식(과제를 함께 불러오는지) 확인 |
| H2 | DB · 데이터 | 학급 1 에 과제 9 를 가리키는데 과제 9 행이 없는 배포(배포 41 로 추정)가 배포 전부터 있었다. 이 고아 데이터는 원래 배포 41 을 직접 다룰 때만 드러났고, 배포 후에는 학급 1 의 목록 조회마다 드러난다. | 배포 전 오류 7건이 모두 배포 41 · 과제 9 · `loadOrThrow`(`app.log:195` · `:205`, `:452` · `:463`). 배포 41 은 이날 한 번도 성공하지 않음(`grep 'redistributed distribution 41'` 0건). 배포 후 오류도 모두 과제 9(`app.log:1276` 외 39건). 실패 학급이 1 뿐(1.3). | ① 배포 41 이 학급 1 이 아니다(그러면 학급 1 목록에 과제 9 가 끼어든 경로가 따로 있어야 함). ② 과제 9 행이 DB 에 존재한다(그러면 "없음" 예외의 원인이 데이터가 아니라 조회 조건 · 필터 쪽). ③ 학급 1 리포트(`3 distributions`)가 같은 배포 목록을 읽으면서도 성공했다(`app.log:6`, `:1191`) — 리포트가 과제를 불러오는 경로라면 고아 배포가 학급 1 에 없다는 뜻. | ① 로그로는 배포 41 의 학급이 안 보임: `grep -n 'distribution 41\|distributions/41' incident-logs/b-deploy-5xx/app.log`. 로컬 시드에서 `grep -nE '\(41,\|assignment.*\b9\b' db/` 로 시드 정의 확인(운영 DB 에 붙지 않음). 운영 데이터 조회는 DB 담당자에게 `distribution` id 41 의 `class_id` · `assignment_id` 와 `assignment` id 9 존재 여부를 요청 ② 같은 요청 ③ `modern/api/src/main/java/com/example/assignment/ReportService.java` 에서 배포 목록을 읽는 쿼리가 `Assignment` 를 함께 불러오는지 확인 |
| H3 | 트래픽 | `교사단말A` 가 같은 재배포를 짧은 간격으로 반복 요청(재시도 · 연타)하면서 동시 요청이 겹쳐 실패했다. | 배포 42 · 43 재배포가 10~25초 간격 2~4건 묶음으로 반복(`nginx-access.log:17155` · `:17170` · `:17181`, `:17365` · `:17366`). 500 46건 모두 `교사단말A`(1.3). | ① 각 묶음의 **첫** 요청이 이미 500 이다(재시도 이전에 실패) — 첫 사례 13:46:38(`nginx-access.log:17155`)이 이 시각 이전 학급 1 재배포 요청 없이 바로 500. ② 배포 전에도 같은 단말이 같은 간격으로 반복했는데 200 이었다. ③ 동시 처리 중인 요청이 없는 시각(묶음 사이 단독 요청)에도 500 이다. | ① `awk '$1=="<교사단말A 실제 주소>" && $6=="\"POST"{print substr($4,14,8), $7, $9}' incident-logs/b-deploy-5xx/nginx-access.log` 로 묶음별 첫 요청 상태 확인 ② 같은 출력에서 13:41 이전 행 비교 ③ `app.log` ERROR 줄의 스레드 이름과 같은 초 다른 요청 유무 대조 |
| H4 | 인프라 · 배포 방식 | 단일 호스트 재기동(무중단 아님) 방식 때문에 기동 사이 앱이 응답하지 못해 502 가 났다. | 배포 방식 기록(`deploy-history.md:18`). 502 8건이 shutdown 완료(`app.log:1241` 13:41:50) ~ 기동 완료(`app.log:1264` 13:42:00) 사이에만 있음(`nginx-access.log:16865` ~ `:16872`). | ① 502 가 재기동 구간(13:41:48 ~ 13:42:00) 밖에도 있다. ② 500(39건)도 재기동 구간과 겹친다 — 이 가설은 502 8건만 설명하고 13:46:38 이후 500 은 설명하지 못하므로, 500 의 원인 후보에서는 빠져야 한다. ③ `API-2` 가 그 시각 요청을 받고 있었다(대기 호스트가 살아 있었다면 502 가 나면 안 됨). | ① `awk '$9==502{print NR, substr($4,14,8)}' incident-logs/b-deploy-5xx/nginx-access.log` ② `awk '$9==500 && NR>16872{print substr($4,14,8)}' incident-logs/b-deploy-5xx/nginx-access.log \| head -1` ③ access 로그에 `$upstream_addr` 가 없어 로그로 확인 불가. nginx upstream 설정 · error 로그 추가 요청 |
| H5 | 배포 · 검증 | 배포 전 검증(스테이징 시드 DB 에서 재배포 3건 수동 확인, `./gradlew test` 24건)에 "과제가 없는 배포가 섞인 학급" 같은 운영 데이터 상태가 없어, 운영에서만 드러나는 실패를 걸러내지 못했다. (H1 · H2 가 맞을 때의 배경 원인 후보) | 검증 기록(`deploy-history.md:17`). 운영 로그에는 배포 전부터 과제 9 없음 예외가 있었음(`app.log:195`). 수업 중 반영 요청으로 배포(`deploy-history.md:14`). | ① 스테이징 시드에도 과제가 없는 배포가 있고, 수동 확인 3건에 그 학급이 포함됐다(그러면 검증 범위 문제가 아님). ② 운영 데이터에 고아 배포가 없다(H2 반증 시 함께 기각). | ① `db/` 시드에서 `distribution` · `assignment` 정의를 읽어 참조가 끊긴 행이 있는지 확인(읽기만, 시드 수정 금지). ② H2 확인 결과를 따름 |

### 3.2 가설 사이의 관계(판정 아님)

- H1 과 H2 는 배타적이지 않고 **조합**일 수 있다: H2(배포 전부터 있던 고아 데이터) 위에 H1(그 데이터를 학급 단위로 읽는 새 호출)이 겹쳐야 "배포 후 학급 1 재배포 전부 500"이 된다. 각 가설의 반증 조건을 독립적으로 확인한다.
- H4 는 502 8건만 다룬다. 500 39건과는 시각이 겹치지 않으므로 별개 사건 후보로 둔다.
- **선후 관계가 핵심 판별점**이다. H1 ③ · H3 ② 는 "배포 전에도 같은 일이 있었는가"를, H3 ① 은 "재시도보다 실패가 먼저인가"를 묻는다.
- 4절에서 확인할 관찰: 배포 후 학급 1 재배포마다 `redistributed` INFO 가 ERROR **직전**에 찍힌다(`app.log:1274` → `:1275`, 6ms). 재배포가 실제로 반영된 뒤 응답만 500 이 났는지, 반복 요청으로 재배포가 여러 번 반영됐는지는 아직 확인하지 않았다.

---

마스킹 적용: IP 19건 · 이메일 1건 · 학생 식별자 3건 · 토큰 0건 · 비밀번호 · 접속 문자열 0건
