package com.example.item;

import java.util.List;

/**
 * {@code GET /api/items/search} 응답. 동작 보존 테스트의 정규화 모양 {@code {rows, count, message}} 와 같다.
 *
 * @param rows    현재 페이지의 행
 * @param count   조건에 맞는 전체 건수(페이지와 무관)
 * @param message 전체 건수가 0 이면 안내 문구, 아니면 null
 */
public record ItemSearchResponse(List<ItemSearchRow> rows, long count, String message) {

    /** 레거시 {@code legacy/item-bank-php/search.php:658} 의 문구. */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    static ItemSearchResponse of(List<ItemSearchRow> rows, long count) {
        return new ItemSearchResponse(rows, count, count == 0 ? NO_RESULT_MESSAGE : null);
    }
}
