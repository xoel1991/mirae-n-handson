# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

답변과 문서는 한국어로 쓴다. Claude Code 는 저장소 루트(`~/work/mirae-n-handson`)에서 실행한다.
도메인 용어: 문항 `item` · 단원 `unit` · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 식별자 `STU-<숫자>`.

## 1. 빌드 · 테스트 명령

```bash
# modern/api — Spring Boot 3.3 · Java 21 · Gradle wrapper
cd modern/api && ./gradlew test                                            # H2 인메모리. DB 없이 통과해야 한다
cd modern/api && ./gradlew test --tests 'com.example.item.ItemServiceTest' # 테스트 클래스 하나만
cd modern/api && ./gradlew build                                           # 테스트 포함 전체 빌드
cd modern/api && ./gradlew bootRun                                         # 8080. 먼저 docker compose --profile modern up -d

# modern/web — React 18 · TypeScript · Vite
cd modern/web && npm ci && npm run dev                                     # 5173
cd modern/web && npm run lint && npm run typecheck && npm test
cd modern/web && npx vitest run src/components/ItemTable.test.tsx          # 테스트 파일 하나만

# characterization — 동작 보존 테스트
cd characterization && npm run baseline -- <모듈명>                        # item-bank | assignment | grade
cd characterization && npm test                                            # 대상: 레거시 기본 주소
cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test      # 대상: 새 API

# Docker Compose — up · down · build · logs 모두 프로필을 붙인다
docker compose --profile php up -d        # 문항 은행 8081 + MariaDB 3306
docker compose --profile thymeleaf up -d  # 과제 배포 8082
docker compose --profile modern up -d     # 현행 샘플 DB(MariaDB)만
docker compose --profile php down
```

- `modern/api` 를 고쳤으면 `./gradlew test` 를 실행하고 통과 · 실패 수를 답변에 적는다. 실행하지 않았으면 "실행하지 않음"이라고 쓴다.
- `modern/web` 을 고쳤으면 `npm run lint`, `npm run typecheck`, `npm test` 를 실행하고 결과를 적는다.
- 테스트 설정은 `modern/api/src/test/resources/application-test.yml`(H2 MariaDB 모드)이다. 테스트가 실제 MariaDB 에 붙게 만들지 않는다.

## 2. 코딩 컨벤션 (`modern/` 기준)

### 계층
- 패키지는 도메인 단위(`com.example.item`, `com.example.assignment`)다. 한 도메인 안에서 Controller → Service → Repository → 엔티티 방향으로만 호출한다.
- 컨트롤러는 서비스만 주입받는다. 컨트롤러 파일에 `Repository` 주입, `JdbcTemplate`, SQL 문자열이 있으면 위반이다.
- 서비스가 다른 도메인의 데이터를 쓸 때는 그 도메인의 서비스를 주입받는다. 다른 도메인의 `Repository` 를 주입하지 않는다.
- 응답은 record DTO(`*Response`)로 돌려준다. 컨트롤러 메서드의 반환 타입에 엔티티가 오면 위반이다.
- 서비스는 트랜잭션 안에서 엔티티를 DTO 로 바꿔 돌려준다(`open-in-view: false` 라 트랜잭션 밖에서는 지연 로딩이 실패한다). 조회 전용 메서드는 `@Transactional(readOnly = true)` 를 쓴다.
- 서비스 로직에서 현재 시각이 필요하면 `Clock` 빈을 주입받는다. 서비스 코드에서 `now()` 를 `Clock` 인자 없이 호출하지 않는다(`common/ErrorResponse` 의 응답 시각은 예외).

### 예외 처리
- 빈 `catch` 블록은 금지한다. 잡았으면 로그를 남기고 다시 던지거나 도메인 예외로 바꿔 던진다.
- 예외 → HTTP 응답 변환은 `common/GlobalExceptionHandler` 에서만 한다. 컨트롤러 안에서 try-catch 로 상태 코드를 만들지 않는다.
- 없는 리소스는 `NotFoundException`(404), 검증 실패는 400, 그 밖의 예상 못 한 예외는 500 이다.

### 로깅
- 로그는 SLF4J(`org.slf4j.Logger`)로만 남긴다. `System.out.println`, `System.err.println`, `e.printStackTrace()` 는 금지한다.
- 로그 메시지에 학생 식별자(`STU-…`) · 이메일 · 토큰 값을 넣지 않는다.
- 레벨: 정상 흐름 `INFO` 이하, 복구 가능한 실패 `WARN`, 요청을 실패시키는 예외 `ERROR`.

### 테스트
- 새 public 서비스 메서드 · 새 엔드포인트마다 테스트를 1개 이상 추가한다.
- 컨트롤러는 `@WebMvcTest`, 서비스는 Mockito 단위 테스트, 리포지토리 쿼리는 `@DataJpaTest`(H2)로 검증한다.
- 테스트 메서드 이름은 확인하는 동작이 드러나는 camelCase 로 짓고 `@DisplayName` 에 한국어 설명을 붙인다.

### 이름 · 형식
- 클래스 `PascalCase`, 메서드 · 필드 `camelCase`, 상수 `UPPER_SNAKE_CASE`. 약어도 첫 글자만 대문자(`ItemDto`).
- 비교 · 계산에 쓰는 숫자 리터럴(0, 1 제외)은 이름 붙인 상수로 뺀다(예: `MAX_LEVEL = 5`).
- 들여쓰기 4칸, 한 줄 120자 이내, 와일드카드 import 금지.

### modern/web
- API 호출은 `src/api/client.ts` 의 `getJson` 만 거친다. 컴포넌트 · 훅에서 `fetch` 를 직접 호출하지 않는다.
- 조회 훅은 `src/hooks/useApiQuery.ts` 위에 만든다.

## 3. 금지 사항

- `legacy/` 는 분석 · 이관 대상이다. 허락 없이 `legacy/` 아래 파일을 수정하지 않는다.
- DB 스키마 변경(테이블 · 컬럼 · 인덱스 추가 · 삭제, 마이그레이션 파일 추가)과 시드 데이터(`db/`) 변경은 먼저 사람에게 묻는다.
- 의존성 추가(`build.gradle` 의 `dependencies`, `package.json` 의 `dependencies` · `devDependencies` 변경)는 먼저 묻는다. 이유와 대안을 함께 적는다.
- `application.yml` 의 DB 접속 정보 · 커넥션 풀 설정을 바꾸지 않는다. 실제 비밀값(운영 계정 · 토큰 · 키)을 코드나 설정 파일에 리터럴로 넣지 않는다. 실습용 더미 값(`app-pass`)은 예외다.
- 운영 DB 호스트(`prod-db` 등)에 접속하는 명령 · 설정을 만들지 않는다.
- `@Transactional` 메서드 안에서 외부 HTTP 호출이나 DB 와 무관한 긴 루프(서명 · 해시 반복 등)를 돌리지 않는다. 커넥션 풀이 작다(`maximum-pool-size: 5`).
- 요청받지 않은 파일을 "정리" 명목으로 고치지 않는다. 포맷팅 · import 정리도 요청 범위 안의 파일에서만 한다.
- `characterization/` 의 스냅샷 파일(`__snapshots__/`)을 깨뜨리거나 고치는 변경은 먼저 사람에게 알린다.

## 4. 아키텍처 안내

```
modern/api/src/main/java/com/example/
├── item/         문항 · 단원 · 태그 (ItemController, UnitController, ItemService, UnitService, *Repository)
├── assignment/   과제 배포 · 재배포 · 학급 리포트 (Distribution*, Report*, Submission*)
├── common/       GlobalExceptionHandler, ErrorResponse, NotFoundException, ClockConfig
└── config/       WebConfig
modern/web/src/   api/(getJson · 타입) → hooks/(useApiQuery 기반) → components/
characterization/ lib/normalize.mjs · lib/target.mjs(공용) · tests/<모듈명>.test.js · __snapshots__/
```

- 기존 엔드포인트: `GET /api/units`, `GET /api/units/{code}/items`, `GET /api/items/{id}`, `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`. 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 둔다.
- 새 조회 API 는 컨트롤러 → 서비스 → 리포지토리와 테스트를 같은 도메인 패키지에 만든다.
- 공개 문항은 `status = 'A'`(`ItemStatus.ACTIVE`)로 판단한다. 삭제 플래그가 아니라 상태 코드다.
- Compose 프로필 `php` · `thymeleaf` · `modern` 은 MariaDB(`itembank`, 3306) 하나를 공유한다. 영구 볼륨이 없어 `down` → `up` 하면 시드 상태로 돌아간다. `mssql` 은 Apple Silicon 에서 에뮬레이션으로 돌아 느리다.
- characterization 은 레거시 HTML 과 새 API JSON 을 `{status, rows[], count, message}` 한 모양으로 정규화해 비교한다. 대상 주소는 `TARGET_BASE_URL`, 경로가 바뀐 화면은 `lib/target.mjs` 의 `PATH_ALIASES` 에 적는다. 테스트 코드에 `localhost:808x` 주소를 직접 적지 않는다.
- 레거시: `legacy/item-bank-php`(PHP 7.4 · 문항 은행 8081), `legacy/assignment-thymeleaf`(Spring MVC + Thymeleaf · 과제 배포 8082), `legacy/grade-mssql`(저장 프로시저 · 성적 집계 8083).
- 레거시 규칙을 옮기거나 설명할 때는 근거를 `파일:줄번호` 로 적는다(예: `legacy/item-bank-php/search.php:214`).

## 5. 완료 기준

- 이관 · 리팩토링 작업은 `characterization` 의 `npm test` 가 전부 통과해야 완료로 보고한다. 하나라도 실패하면 완료라고 쓰지 않는다.
- 테스트가 실패하면 실패한 케이스 이름과 스냅샷 차이를 그대로 보고한다. "거의 됐다" 같은 요약으로 대신하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일(`__snapshots__/`)을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 작업을 멈추고 사람에게 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않고 그대로 옮긴다. 그런 동작은 "의심 동작" 목록으로 따로 보고한다.
