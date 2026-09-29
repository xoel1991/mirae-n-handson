package com.example.item;

import java.util.Arrays;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;

/**
 * 문항 검색 정렬 기준. 레거시 {@code legacy/item-bank-php/search.php:276-326} 의 ORDER BY 를 그대로 옮겼다.
 * {@code dir} 이 없을 때의 방향은 기준마다 다르다(id · title · unit 은 오름차순, level · created 는 내림차순).
 */
enum ItemSearchSort {

    ID("id"),
    TITLE("title"),
    UNIT("unit"),
    LEVEL("level"),
    CREATED("created"),
    /** 기본 정렬 — 어려운 문항부터, 같은 난이도면 번호 순. 알 수 없는 기준도 이것을 쓴다. */
    DEFAULT("");

    static final String ASC = "asc";
    static final String DESC = "desc";

    private final String param;

    ItemSearchSort(String param) {
        this.param = param;
    }

    /** 소문자 · trim 을 마친 {@code sort} 값 → 정렬 기준. 모르는 값은 {@link #DEFAULT}. */
    static ItemSearchSort fromParam(String sort) {
        return Arrays.stream(values())
            .filter(s -> s.param.equals(sort))
            .findFirst()
            .orElse(DEFAULT);
    }

    /**
     * 정렬 방향을 붙여 Spring {@link Sort} 로 바꾼다.
     *
     * @param dir 소문자 · trim 을 마친 값. {@code asc} · {@code desc} 가 아니면 기준별 기본 방향.
     */
    Sort toSort(String dir) {
        return switch (this) {
            case ID -> Sort.by(DESC.equals(dir) ? Order.desc("id") : Order.asc("id"));
            case TITLE -> Sort.by(DESC.equals(dir) ? Order.desc("title") : Order.asc("title"), Order.asc("id"));
            case UNIT -> Sort.by(
                DESC.equals(dir) ? Order.desc("unit.code") : Order.asc("unit.code"),
                Order.desc("level"),
                Order.asc("id"));
            case LEVEL -> Sort.by(ASC.equals(dir) ? Order.asc("level") : Order.desc("level"), Order.asc("id"));
            case CREATED -> Sort.by(
                ASC.equals(dir) ? Order.asc("createdAt") : Order.desc("createdAt"),
                Order.asc("id"));
            case DEFAULT -> Sort.by(Order.desc("level"), Order.asc("id"));
        };
    }
}
