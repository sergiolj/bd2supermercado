package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import br.edu.ucsal.bd2.exceptions.InvalidSortException;
import br.edu.ucsal.bd2.util.SortPolicy;

class SortPolicyTest {

	private final SortPolicy policy = SortPolicy.allowing("name", "category.name");

	@Test
	@DisplayName("Should keep page, size and the requested order, adding the id as the last criterion")
	void shouldAppendTieBreaker() {
		Pageable requested = PageRequest.of(2, 5, Sort.by(Sort.Order.desc("name")));

		Pageable applied = policy.apply(requested);

		assertEquals(2, applied.getPageNumber());
		assertEquals(5, applied.getPageSize());
		assertEquals(Sort.by(Sort.Order.desc("name"), Sort.Order.asc("id")), applied.getSort());
	}

	@Test
	@DisplayName("Should order only by the id when no sort is requested")
	void shouldOrderByIdWhenUnsorted() {
		Pageable applied = policy.apply(PageRequest.of(0, 20));

		assertEquals(Sort.by(Sort.Order.asc("id")), applied.getSort());
	}

	@Test
	@DisplayName("Should accept nested fields and several criteria")
	void shouldAcceptNestedFields() {
		Pageable requested = PageRequest.of(0, 20, Sort.by(Sort.Order.asc("category.name"), Sort.Order.desc("name")));

		Pageable applied = policy.apply(requested);

		assertEquals(
				Sort.by(Sort.Order.asc("category.name"), Sort.Order.desc("name"), Sort.Order.asc("id")),
				applied.getSort());
	}

	@Test
	@DisplayName("Should reject a field that is not in the allowed list and name the allowed ones")
	void shouldRejectUnknownField() {
		Pageable requested = PageRequest.of(0, 20, Sort.by("barcode"));

		InvalidSortException e = assertThrows(InvalidSortException.class, () -> policy.apply(requested));

		assertEquals("Não é possível ordenar por 'barcode'. Campos permitidos: name, category.name.", e.getMessage());
	}

	@Test
	@DisplayName("Should reject malformed expressions (like the one Swagger sends by default) and the internal id")
	void shouldRejectMalformedExpressions() {
		for (String malformed : new String[] {"[\"string\"]", "name);drop", "id"}) {
			Pageable requested = PageRequest.of(0, 20, Sort.by(malformed));

			assertThrows(InvalidSortException.class, () -> policy.apply(requested), malformed);
		}
	}

	@Test
	@DisplayName("Should not touch a request that has no pagination")
	void shouldIgnoreUnpaged() {
		assertSame(Pageable.unpaged(), policy.apply(Pageable.unpaged()));
	}
}
