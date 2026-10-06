---
name: verify
description: 변경분을 팀 승인 체크리스트로 검증해 승인 · 반려를 판정합니다. AI가 만든 코드나 외주사 PR을 머지하기 전에 사용합니다.
argument-hint: "[비교 범위 또는 patch 경로]"
---

# 검증루프

검증 대상: $ARGUMENTS
(비어 있으면 git diff upstream/main...HEAD)

### 절차
1. 요청 범위를 확인한다. 주어지지 않았으면 판정하지 말고 먼저 묻는다.
2. 변경 파일 목록과 diff 를 읽는다.
3. 체크리스트 항목별 대조는 직접 하지 말고 reviewer Sub-agent에 위임한다. 비교 범위와 요청 범위를 그대로 넘긴다. reviewer 는 저장소 루트의 templates/approval-checklist.md 를 기준으로 대조한다.
4. 테스트 실행은 직접 하지 말고 tester Sub-agent에 위임한다. 새 로직에 테스트가 없으면 테스트 파일을 추가하게 하고, 실행 결과를 돌려받는다. 변경된 폴더의 테스트만 실행하게 한다.
   - modern/api 가 바뀌었으면: ./gradlew test
   - modern/web 이 바뀌었으면: npm test
   - 이관 코드: characterization 에서 npm test (레거시 대상), TARGET_BASE_URL=http://localhost:8080 npm test (새 API 대상). 새 API가 꺼져 있으면 실행하지 말고 사람에게 기동을 요청한다.
5. reviewer 와 tester 의 보고를 모두 받은 뒤 메인 세션이 승인 기준을 모두 최종 판정한다. 하나라도 미충족이면 반려. 두 보고가 엇갈리면 엇갈린 지점을 그대로 적고 사람에게 묻는다.

reviewer 또는 tester Sub-agent가 없는 환경에서는 3 · 4단계를 기존 방식대로 직접 수행한다.

### 출력 형식
- 판정: 승인 / 반려
- 기준별 결과 표: 테스트 통과 / 치명 이슈 0건 / 요청 범위 이탈 없음 / 컨벤션 준수
- 이슈 목록: 심각도 · 파일:줄번호 · 근거 · 수정 방향

### 하지 말 것
- 코드를 수정하지 않는다(tester 가 추가하는 테스트 파일은 예외).
- 근거 줄번호가 없는 지적은 "확인 필요"로 남긴다.
- 테스트를 실행하지 않고 통과했다고 쓰지 않는다.
