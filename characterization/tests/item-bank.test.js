// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 규칙 ID(BR-xx)는 docs/item-bank/BUSINESS-RULES.md 를 따른다.
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 레거시 기본 주소).
// - 응답은 fetchNormalized 로 {status, rows, count, message} 로 바꾼 뒤 스냅샷과 비교한다.
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 레거시와 새 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기를 두지 않는다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

const KEYWORD_MAX_LENGTH = 100; // BR-04
const MAX_PAGE = 999; // BR-19
const VERY_LONG_LENGTH = 5000;

const search = (params) => fetchNormalized(MODULE, PATH, params);

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상 입력', () => {
    it('단원 하나로 기본 검색 — 공개 문항 · 난이도 5 제외 · 기본 정렬 (BR-01, BR-07, BR-08, BR-14)', async () => {
      expect(await search({ unit: 'M5-1' })).toMatchSnapshot();
    });

    it('단원 · 난이도 · 태그 AND 결합 (BR-02, BR-09, BR-11)', async () => {
      expect(await search({ unit: 'M5-1', level: '3', tag: '계산' })).toMatchSnapshot();
    });

    it('키워드 부분 일치 + 제목 내림차순 정렬 (BR-03, BR-15, BR-08)', async () => {
      expect(await search({ q: '분수', sort: 'title', dir: 'desc' })).toMatchSnapshot();
    });
  });

  describe('경계값', () => {
    it('난이도 5 명시 — 기본 검색에서 빠지는 난이도 5 만 조회 (BR-08, BR-09)', async () => {
      expect(await search({ level: '5' })).toMatchSnapshot();
    });

    it('난이도 05 — 형식은 틀렸지만 정수 변환하면 5 (BR-10)', async () => {
      expect(await search({ level: '05' })).toMatchSnapshot();
    });

    it('난이도 0 — 1~5 범위 바로 아래 (BR-10)', async () => {
      expect(await search({ level: '0' })).toMatchSnapshot();
    });

    it('키워드 한 글자 — 경고만 내고 검색은 그대로 (BR-06, BR-03)', async () => {
      expect(await search({ q: '분' })).toMatchSnapshot();
    });

    it('키워드 정확히 100자 — 자르지 않는 상한 (BR-04)', async () => {
      expect(await search({ q: '분'.repeat(KEYWORD_MAX_LENGTH) })).toMatchSnapshot();
    });

    it('키워드 101자 — 100자로 잘리는 경계 (BR-04)', async () => {
      expect(await search({ q: '분'.repeat(KEYWORD_MAX_LENGTH + 1) })).toMatchSnapshot();
    });

    it('기본 검색 2페이지 — 결과가 정확히 한 페이지(20건)일 때 다음 페이지 (BR-18, BR-08)', async () => {
      expect(await search({ page: '2' })).toMatchSnapshot();
    });

    it('페이지 999 — 보정 없는 상한 (BR-19)', async () => {
      expect(await search({ page: String(MAX_PAGE) })).toMatchSnapshot();
    });

    it('페이지 1000 — 999 로 보정되는 경계 (BR-19)', async () => {
      expect(await search({ page: String(MAX_PAGE + 1) })).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('파라미터 없음 — 기본 검색 (BR-01, BR-08, BR-14, BR-18)', async () => {
      expect(await search({})).toMatchSnapshot();
    });

    it('모든 파라미터 빈 값 · 키워드 공백만 (BR-03, BR-07, BR-08, BR-16, BR-19)', async () => {
      const params = { q: '  ', unit: '', level: '', tag: '', sort: '', dir: '', page: '' };
      expect(await search(params)).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('음수 페이지 · 음수 난이도 (BR-19, BR-10)', async () => {
      expect(await search({ page: '-1', level: '-3' })).toMatchSnapshot();
    });

    it('아주 긴 키워드 5000자 (BR-04)', async () => {
      expect(await search({ q: 'a'.repeat(VERY_LONG_LENGTH) })).toMatchSnapshot();
    });

    it('없는 단원 코드 · 없는 태그 — 0건 안내 문구 (BR-07, BR-11, BR-20)', async () => {
      expect(await search({ unit: 'X9-9', tag: '없는태그' })).toMatchSnapshot();
    });

    it('소문자 단원 코드 + 배열 키워드 첫 값 % 와일드카드 (BR-07, BR-05, BR-13)', async () => {
      expect(await search({ unit: 'm5-1', 'q[]': ['%', 'zzz'] })).toMatchSnapshot();
    });
  });
});
