package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import br.edu.ucsal.bd2.exceptions.InvalidSortException;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.repository.CategoryRepository;
import br.edu.ucsal.bd2.repository.ProductRepository;
import br.edu.ucsal.bd2.service.ProductService;

// Teste de unidade puro: sem Spring e sem banco, só o service com os repositórios simulados.
class ProductServiceTest {

	private final ProductRepository repository = mock(ProductRepository.class);
	private final ProductService service = new ProductService(repository, mock(CategoryRepository.class));

	@Test
	@DisplayName("Should ask the repository for the requested page, with the id as tie-breaker")
	void shouldListProductsPage() {
		Page<Product> expected = new PageImpl<>(List.of(new Product()));
		when(repository.findByDeletedAtIsNull(any(Pageable.class))).thenReturn(expected);

		Page<Product> result = service.findAll(PageRequest.of(1, 10, Sort.by("category.name")));

		assertSame(expected, result);

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(repository).findByDeletedAtIsNull(captor.capture());
		assertEquals(1, captor.getValue().getPageNumber());
		assertEquals(10, captor.getValue().getPageSize());
		assertEquals(Sort.by(Sort.Order.asc("category.name"), Sort.Order.asc("id")), captor.getValue().getSort());
	}

	@Test
	@DisplayName("Should never reach the database when the sort field is not allowed")
	void shouldRejectInvalidSortBeforeQuerying() {
		assertThrows(InvalidSortException.class,
				() -> service.findAll(PageRequest.of(0, 20, Sort.by("[\"string\"]"))));

		verifyNoInteractions(repository);
	}
}
