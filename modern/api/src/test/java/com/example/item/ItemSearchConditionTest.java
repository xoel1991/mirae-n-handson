package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** 검색 파라미터 해석 — 레거시 search.php 와 같은 규칙으로 바뀌는지. */
class ItemSearchConditionTest {

    private static MultiValueMap<String, String> params(String... keyValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.add(keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    @Test
    @DisplayName("파라미터가 없으면 조건 없음 · 난이도 기본(null) · 기본 정렬 · 1페이지")
    void noParamsUsesDefaults() {
        ItemSearchCondition condition = ItemSearchCondition.from(params());

        assertThat(condition.keyword()).isNull();
        assertThat(condition.unitCode()).isNull();
        assertThat(condition.level()).isNull();
        assertThat(condition.tag()).isNull();
        assertThat(condition.sort()).isEqualTo(Sort.by(Order.desc("level"), Order.asc("id")));
        assertThat(condition.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 값 · 공백만 있는 값은 조건에서 빠진다")
    void blankValuesAreIgnored() {
        ItemSearchCondition condition = ItemSearchCondition.from(
            params("q", "  ", "unit", "", "level", "", "tag", "", "sort", "", "dir", "", "page", ""));

        assertThat(condition.keyword()).isNull();
        assertThat(condition.unitCode()).isNull();
        assertThat(condition.level()).isNull();
        assertThat(condition.tag()).isNull();
        assertThat(condition.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("키워드는 앞뒤 공백을 빼고 100자(코드포인트)까지만 쓴다")
    void keywordIsTrimmedAndTruncatedTo100Chars() {
        String exact = "분".repeat(ItemSearchCondition.KEYWORD_MAX_LENGTH);

        assertThat(ItemSearchCondition.from(params("q", " " + exact + "분 ")).keyword()).isEqualTo(exact);
        assertThat(ItemSearchCondition.from(params("q", exact)).keyword()).isEqualTo(exact);
    }

    @Test
    @DisplayName("태그는 50자까지만 쓴다")
    void tagIsTruncatedTo50Chars() {
        String exact = "a".repeat(ItemSearchCondition.TAG_MAX_LENGTH);

        assertThat(ItemSearchCondition.from(params("tag", exact + "b")).tag()).isEqualTo(exact);
    }

    @Test
    @DisplayName("PHP trim 처럼 유니코드 공백(전각 공백)은 지우지 않는다")
    void phpTrimKeepsUnicodeSpaces() {
        assertThat(ItemSearchCondition.phpTrim("\t　분수\n")).isEqualTo("　분수");
    }

    @Test
    @DisplayName("name[] 배열이면 첫 값, 같은 이름이 반복되면 마지막 값을 쓴다")
    void arrayParamUsesFirstAndRepeatedParamUsesLast() {
        assertThat(ItemSearchCondition.from(params("q[]", "%", "q[]", "zzz")).keyword()).isEqualTo("%");
        assertThat(ItemSearchCondition.from(params("q", "a", "q", "b")).keyword()).isEqualTo("b");
    }

    @Test
    @DisplayName("단원 코드는 대소문자 · 형식을 고치지 않고 그대로 둔다")
    void unitCodeIsKeptAsIs() {
        assertThat(ItemSearchCondition.from(params("unit", " m5-1 ")).unitCode()).isEqualTo("m5-1");
    }

    @Test
    @DisplayName("난이도: 1~5 는 그대로, 그 밖의 값은 PHP (int) 변환 값")
    void levelFollowsPhpIntConversion() {
        assertThat(ItemSearchCondition.from(params("level", "3")).level()).isEqualTo(3L);
        assertThat(ItemSearchCondition.from(params("level", "05")).level()).isEqualTo(5L);
        assertThat(ItemSearchCondition.from(params("level", "0")).level()).isZero();
        assertThat(ItemSearchCondition.from(params("level", "-3")).level()).isEqualTo(-3L);
        assertThat(ItemSearchCondition.from(params("level", "abc")).level()).isZero();
        assertThat(ItemSearchCondition.from(params("level", "3abc")).level()).isEqualTo(3L);
        assertThat(ItemSearchCondition.from(params("level", "1e1")).level()).isEqualTo(10L);
    }

    @Test
    @DisplayName("PHP (int): 정수 범위를 넘으면 끝값, 무한대는 0")
    void phpIntvalCapsOverflow() {
        assertThat(ItemSearchCondition.phpIntval("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(ItemSearchCondition.phpIntval("-99999999999999999999")).isEqualTo(Long.MIN_VALUE);
        assertThat(ItemSearchCondition.phpIntval("1e999")).isZero();
        assertThat(ItemSearchCondition.phpIntval("3.9")).isEqualTo(3L);
    }

    @Test
    @DisplayName("페이지: 숫자가 아니면 1, 1 미만은 1, 999 초과는 999")
    void pageIsClamped() {
        assertThat(ItemSearchCondition.from(params("page", "2")).page()).isEqualTo(2);
        assertThat(ItemSearchCondition.from(params("page", "0")).page()).isEqualTo(1);
        assertThat(ItemSearchCondition.from(params("page", "-1")).page()).isEqualTo(1);
        assertThat(ItemSearchCondition.from(params("page", "abc")).page()).isEqualTo(1);
        assertThat(ItemSearchCondition.from(params("page", "999")).page()).isEqualTo(ItemSearchCondition.MAX_PAGE);
        assertThat(ItemSearchCondition.from(params("page", "1000")).page()).isEqualTo(ItemSearchCondition.MAX_PAGE);
        assertThat(ItemSearchCondition.from(params("page", "99999999999999999999")).page())
            .isEqualTo(ItemSearchCondition.MAX_PAGE);
        assertThat(ItemSearchCondition.from(params("page", "2\n")).page()).isEqualTo(2);
        assertThat(ItemSearchCondition.from(params("page", " 2")).page()).isEqualTo(1);
    }

    @Test
    @DisplayName("정렬: 기준별 ORDER BY 와 기본 방향, 대소문자 무시, 모르는 값은 기본 정렬")
    void sortMapsToLegacyOrderBy() {
        assertThat(ItemSearchCondition.from(params("sort", "UNIT", "dir", "DESC")).sort())
            .isEqualTo(Sort.by(Order.desc("unit.code"), Order.desc("level"), Order.asc("id")));
        assertThat(ItemSearchCondition.from(params("sort", "title", "dir", "up")).sort())
            .isEqualTo(Sort.by(Order.asc("title"), Order.asc("id")));
        assertThat(ItemSearchCondition.from(params("sort", "created")).sort())
            .isEqualTo(Sort.by(Order.desc("createdAt"), Order.asc("id")));
        assertThat(ItemSearchCondition.from(params("sort", "level", "dir", "asc")).sort())
            .isEqualTo(Sort.by(Order.asc("level"), Order.asc("id")));
        assertThat(ItemSearchCondition.from(params("sort", "id", "dir", "desc")).sort())
            .isEqualTo(Sort.by(Order.desc("id")));
        assertThat(ItemSearchCondition.from(params("sort", "zzz")).sort())
            .isEqualTo(Sort.by(Order.desc("level"), Order.asc("id")));
    }

    @Test
    @DisplayName("페이지 요청: 한 페이지 20건, offset = (page - 1) × 20")
    void toPageableUsesPageSize20() {
        ItemSearchCondition condition = ItemSearchCondition.from(params("page", "3"));

        assertThat(condition.toPageable().getPageSize()).isEqualTo(ItemSearchCondition.PAGE_SIZE);
        assertThat(condition.toPageable().getOffset()).isEqualTo(2L * ItemSearchCondition.PAGE_SIZE);
    }
}
