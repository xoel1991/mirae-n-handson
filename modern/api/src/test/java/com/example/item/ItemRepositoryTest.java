package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** 저장소 슬라이스 — H2(MariaDB 모드)에 실제 SQL 을 날린다. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private UnitRepository unitRepository;

    private Unit fraction;

    @BeforeEach
    void seed() {
        fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분모가 같은 분수의 덧셈", 2, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, calc, word));
        entityManager.persist(ItemFixtures.item(null, fraction, "검수 중 문항", 3, ItemStatus.REVIEWING));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 문항", 5, ItemStatus.DELETED));
        entityManager.persist(ItemFixtures.item(null, fraction, "난이도 5 분수", 5, ItemStatus.ACTIVE, word));
        entityManager.persist(ItemFixtures.item(null, decimal, "소수 곱셈", 1, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("findByUnitCodeAndStatus: 공개 문항만, level DESC · id ASC, 태그 함께 로드")
    void findsActiveItemsOrderedWithTags() {
        List<Item> items = itemRepository.findByUnitCodeAndStatus("M5-1", ItemStatus.ACTIVE);

        assertThat(items).hasSize(3);
        assertThat(items).extracting(Item::getTitle)
            .containsExactly("난이도 5 분수", "분수 문장제", "분모가 같은 분수의 덧셈");
        assertThat(items).allSatisfy(item -> assertThat(item.getStatus()).isEqualTo("A"));
        assertThat(items.get(1).getTags()).extracting(Tag::getName).containsExactly("계산", "문장제");
    }

    @Test
    @DisplayName("findWithDetailsById: 단원 · 태그가 한 번에 로드된다(지연 로딩 아님)")
    void findWithDetailsLoadsUnitAndTags() {
        Integer id = itemRepository.findByUnitCodeAndStatus("M5-1", ItemStatus.ACTIVE).get(1).getId();
        entityManager.clear();

        Item item = itemRepository.findWithDetailsById(id).orElseThrow();

        assertThat(Hibernate.isInitialized(item.getUnit())).isTrue();
        assertThat(Hibernate.isInitialized(item.getTags())).isTrue();
        assertThat(item.getUnit().getCode()).isEqualTo("M5-1");
        assertThat(item.getTags()).hasSize(2);
    }

    @Test
    @DisplayName("countByUnitIdAndStatus: 상태별 문항 수")
    void countsByStatus() {
        assertThat(itemRepository.countByUnitIdAndStatus(fraction.getId(), ItemStatus.ACTIVE)).isEqualTo(3);
        assertThat(itemRepository.countByUnitIdAndStatus(fraction.getId(), ItemStatus.DELETED)).isEqualTo(1);
        assertThat(unitRepository.findByCode("M5-3")).isPresent();
    }

    private Page<Item> search(String... keyValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.add(keyValues[i], keyValues[i + 1]);
        }
        ItemSearchCondition condition = ItemSearchCondition.from(params);
        return itemRepository.findAll(ItemSearchSpecifications.of(condition), condition.toPageable());
    }

    @Test
    @DisplayName("검색 기본: 공개 문항 중 난이도 5 를 뺀 것만, level DESC · id ASC")
    void searchDefaultExcludesLevel5AndNonPublic() {
        Page<Item> page = search();

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Item::getTitle)
            .containsExactly("분수 문장제", "분모가 같은 분수의 덧셈", "소수 곱셈");
    }

    @Test
    @DisplayName("검색 난이도 5 명시: 공개 난이도 5 문항만")
    void searchLevel5ReturnsOnlyLevel5() {
        assertThat(search("level", "5").getContent()).extracting(Item::getTitle).containsExactly("난이도 5 분수");
    }

    @Test
    @DisplayName("검색 범위 밖 난이도 · 모르는 단원: 오류 없이 0건")
    void searchOutOfRangeReturnsEmpty() {
        assertThat(search("level", "0").getTotalElements()).isZero();
        assertThat(search("level", "99999999999999999999").getTotalElements()).isZero();
        assertThat(search("unit", "X9-9").getTotalElements()).isZero();
    }

    @Test
    @DisplayName("검색 단원 · 태그 AND: 태그는 이름이 정확히 같은 것만, 태그는 id 순으로 로드")
    void searchCombinesUnitAndTag() {
        Page<Item> page = search("unit", "M5-1", "tag", "문장제");

        assertThat(page.getContent()).extracting(Item::getTitle).containsExactly("분수 문장제");
        assertThat(page.getContent().get(0).getTags()).extracting(Tag::getName).containsExactly("계산", "문장제");
        assertThat(search("tag", "문장").getTotalElements()).isZero();
    }

    @Test
    @DisplayName("검색 키워드: 제목 또는 지문 부분 일치")
    void searchKeywordMatchesTitleOrStem() {
        assertThat(search("q", "소수").getContent()).extracting(Item::getTitle).containsExactly("소수 곱셈");
        assertThat(search("q", "문제 본문").getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("검색 정렬 unit desc: 단원 코드 내림차순, 같은 단원이면 level DESC")
    void searchSortsByUnitDesc() {
        assertThat(search("sort", "unit", "dir", "desc").getContent()).extracting(Item::getTitle)
            .containsExactly("소수 곱셈", "분수 문장제", "분모가 같은 분수의 덧셈");
    }

    @Test
    @DisplayName("검색 페이지가 전체보다 뒤면 행은 없고 전체 건수는 그대로")
    void searchPageBeyondTotalKeepsCount() {
        Page<Item> page = search("page", "2");

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
    }
}
