package com.example.item;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

/**
 * 문항 검색 조건. 레거시 {@code legacy/item-bank-php/search.php} 의 {@code buildSearchQuery} 가
 * GET 파라미터를 해석하는 방식을 그대로 따른다(PHP trim · mb 글자 수 · {@code (int)} 변환 포함).
 *
 * @param keyword  제목 · 지문 부분 일치 키워드. 없으면 null
 * @param unitCode 단원 코드. 형식 · 대소문자를 고치지 않고 그대로 비교한다. 없으면 null
 * @param level    난이도. null 이면 기본 검색({@code level < 5}), 아니면 {@code level = 값}
 * @param tag      태그 이름(정확히 일치). 없으면 null
 * @param sort     정렬
 * @param page     1 ~ {@link #MAX_PAGE}
 */
public record ItemSearchCondition(String keyword, String unitCode, Long level, String tag, Sort sort, int page) {

    static final int PAGE_SIZE = 20;
    static final int MAX_PAGE = 999;
    static final int KEYWORD_MAX_LENGTH = 100;
    static final int TAG_MAX_LENGTH = 50;

    /** PHP {@code trim()} 기본 대상 문자: 공백, \t, \n, \r, \0, \x0B. 유니코드 공백은 지우지 않는다. */
    private static final String PHP_TRIM_CHARS = " \t\n\r\0\u000B";
    private static final Pattern LEVEL_PATTERN = Pattern.compile("[1-5]");
    /** PHP {@code /^[0-9]+$/} — {@code $} 는 끝의 줄바꿈 하나를 허용한다. */
    private static final Pattern PAGE_PATTERN = Pattern.compile("[0-9]+\n?");
    /** PHP 숫자 문자열의 앞부분(앞 공백 · 부호 · 소수점 · 지수). {@code (int)} 변환이 읽는 범위. */
    private static final Pattern PHP_NUMERIC_PREFIX =
        Pattern.compile("^[ \\t\\n\\r\\u000B\\f]*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)");
    private static final Pattern PHP_INTEGER = Pattern.compile("[+-]?\\d+");

    /** 요청 파라미터 → 검색 조건. 파라미터 이름은 레거시와 같다(q, unit, level, tag, sort, dir, page). */
    public static ItemSearchCondition from(MultiValueMap<String, String> params) {
        String keyword = truncate(phpTrim(param(params, "q", "")), KEYWORD_MAX_LENGTH);
        String unitCode = phpTrim(param(params, "unit", ""));
        String levelRaw = phpTrim(param(params, "level", ""));
        String tag = truncate(phpTrim(param(params, "tag", "")), TAG_MAX_LENGTH);
        String sortRaw = asciiLower(phpTrim(param(params, "sort", "")));
        String dirRaw = asciiLower(phpTrim(param(params, "dir", "")));
        String pageRaw = param(params, "page", "1");

        return new ItemSearchCondition(
            emptyToNull(keyword),
            emptyToNull(unitCode),
            parseLevel(levelRaw),
            emptyToNull(tag),
            ItemSearchSort.fromParam(sortRaw).toSort(dirRaw),
            parsePage(pageRaw));
    }

    Pageable toPageable() {
        return PageRequest.of(page - 1, PAGE_SIZE, sort);
    }

    /**
     * PHP {@code $_GET} 과 같게 값을 고른다. {@code name[]} 으로 오면 배열이므로 첫 값,
     * {@code name} 이 여러 번 오면 마지막 값이 이긴다.
     */
    private static String param(MultiValueMap<String, String> params, String name, String absent) {
        List<String> arrayValues = params.get(name + "[]");
        if (arrayValues != null && !arrayValues.isEmpty()) {
            return arrayValues.get(0);
        }
        List<String> values = params.get(name);
        if (values != null && !values.isEmpty()) {
            return values.get(values.size() - 1);
        }
        return absent;
    }

    /** 빈 값이면 기본 검색(null). {@code ^[1-5]$} 이 아니면 PHP {@code (int)} 로 바꾼 값으로 비교한다. */
    private static Long parseLevel(String level) {
        if (level.isEmpty()) {
            return null;
        }
        if (LEVEL_PATTERN.matcher(level).matches()) {
            return Long.valueOf(level);
        }
        return phpIntval(level);
    }

    /** 숫자 형식이 아니면 1, 1 미만은 1, {@link #MAX_PAGE} 초과는 {@link #MAX_PAGE}. */
    private static int parsePage(String pageRaw) {
        long page = 1;
        if (PAGE_PATTERN.matcher(pageRaw).matches()) {
            page = phpIntval(pageRaw);
        }
        if (page < 1) {
            return 1;
        }
        return (int) Math.min(page, MAX_PAGE);
    }

    /**
     * PHP 7 {@code (int)$string}. 앞쪽 숫자 부분만 읽고(없으면 0), 소수 · 지수 표기는 실수로 읽어 버린다.
     * 64비트 정수 범위를 넘으면 끝값으로 자르고, 무한대는 0 이다.
     */
    static long phpIntval(String value) {
        Matcher matcher = PHP_NUMERIC_PREFIX.matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        String number = matcher.group(1);
        if (PHP_INTEGER.matcher(number).matches()) {
            BigInteger parsed = new BigInteger(number);
            if (parsed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                return Long.MAX_VALUE;
            }
            if (parsed.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
                return Long.MIN_VALUE;
            }
            return parsed.longValue();
        }
        double parsed = Double.parseDouble(number);
        if (Double.isInfinite(parsed) || Double.isNaN(parsed)) {
            return 0;
        }
        return (long) parsed;
    }

    static String phpTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && PHP_TRIM_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && PHP_TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** PHP {@code mb_substr(..., 0, max, 'UTF-8')} — 글자(코드포인트) 기준으로 자른다. */
    private static String truncate(String value, int maxLength) {
        if (value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }

    /** PHP {@code strtolower} — ASCII 만 소문자로 바꾼다. */
    private static String asciiLower(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(c >= 'A' && c <= 'Z' ? Character.toLowerCase(c) : c);
        }
        return sb.toString();
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
