package com.example.item;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * 문항 검색 조건 → JPA {@link Specification}. 레거시 {@code legacy/item-bank-php/search.php} 의 WHERE 조립과
 * 공개 문항 뷰 {@code v_item_public}({@code db/mariadb/init/01-schema.sql:52-70})을 옮겼다.
 * 대소문자 무시 · 정렬 규칙은 DB 정렬 규칙(utf8mb4_unicode_ci)에 맡긴다. 자바에서 값을 바꾸지 않는다.
 */
final class ItemSearchSpecifications {

    /** 난이도 조건이 비었을 때 이 값 미만만 검색한다(레거시 그대로 — 난이도 5 는 빠진다). */
    static final int DEFAULT_LEVEL_CEILING = 5;

    private ItemSearchSpecifications() {
    }

    /** 모든 조건을 AND 로 묶는다. */
    static Specification<Item> of(ItemSearchCondition condition) {
        List<Specification<Item>> specs = new ArrayList<>();
        specs.add(isPublic());
        if (condition.keyword() != null) {
            specs.add(keywordLike(condition.keyword()));
        }
        if (condition.unitCode() != null) {
            specs.add(unitCodeEquals(condition.unitCode()));
        }
        specs.add(levelMatches(condition.level()));
        if (condition.tag() != null) {
            specs.add(hasTag(condition.tag()));
        }
        return Specification.allOf(specs);
    }

    /** 공개 문항만. 뷰의 {@code WHERE i.status = 'A'}. */
    static Specification<Item> isPublic() {
        return (root, query, cb) -> cb.equal(root.get("status"), ItemStatus.ACTIVE);
    }

    /** 제목 또는 지문 부분 일치. {@code %} · {@code _} 는 이스케이프하지 않는다(레거시 그대로). */
    static Specification<Item> keywordLike(String keyword) {
        String like = "%" + keyword + "%";
        return (root, query, cb) -> cb.or(cb.like(root.get("title"), like), cb.like(root.get("stem"), like));
    }

    static Specification<Item> unitCodeEquals(String unitCode) {
        return (root, query, cb) -> cb.equal(root.get("unit").get("code"), unitCode);
    }

    /** null 이면 {@code level < 5}, 아니면 {@code level = 값}. int 범위 밖의 값은 어떤 문항과도 같지 않다. */
    static Specification<Item> levelMatches(Long level) {
        if (level == null) {
            return (root, query, cb) -> cb.lessThan(root.get("level"), DEFAULT_LEVEL_CEILING);
        }
        if (level < Integer.MIN_VALUE || level > Integer.MAX_VALUE) {
            return (root, query, cb) -> cb.disjunction();
        }
        int value = level.intValue();
        return (root, query, cb) -> cb.equal(root.get("level"), value);
    }

    /** 그 이름의 태그가 달린 문항만(EXISTS). */
    static Specification<Item> hasTag(String tagName) {
        return (root, query, cb) -> {
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root<Item> tagged = sub.correlate(root);
            Join<Item, Tag> tag = tagged.join("tags");
            sub.select(tag.get("id")).where(cb.equal(tag.get("name"), tagName));
            return cb.exists(sub);
        };
    }
}
