package br.edu.ucsal.bd2;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.edu.ucsal.bd2.controller.ProductController;
import br.edu.ucsal.bd2.dto.request.ProductRequest;
import br.edu.ucsal.bd2.exceptions.InvalidSortException;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.Product;

import br.edu.ucsal.bd2.service.ProductService;
import br.edu.ucsal.bd2.util.MeasurementType;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

@WebMvcTest(ProductController.class)
class ProductContollerTest {

	@Autowired
	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@MockitoBean
	private ProductService service;

	// Monta um produto com id interno preenchido: os testes garantem que ele NUNCA aparece na resposta.
	private Product newProduct() {
		Category category = new Category(1L, "Bomboniere");
		category.setUuid(UUID.randomUUID());

		Product product = new Product("Bombom Alpino", category, "1112223334445", "Bombom de chocolate com recheio", MeasurementType.UNIT);
		product.setId(1L);
		product.setUuid(UUID.randomUUID());
		return product;
	}


	@Test
	@DisplayName("Should return HPPT 201 Created and Location header when adding a product")
	void shouldCreateProduct() throws Exception{
		Product product = newProduct();

		ProductRequest requestDTO = ProductRequest.convertToDTO(product);

        when(service.createProduct(any(ProductRequest.class))).thenReturn(product);

        mockMvc.perform(post("/api/product")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
                .andExpect(status().isCreated())

                .andExpect(jsonPath("$.uuid").value(product.getUuid().toString()))
                .andExpect(jsonPath("$.name").value("Bombom Alpino"))
                .andExpect(jsonPath("$.description").value("Bombom de chocolate com recheio"))
                .andExpect(jsonPath("$.categoryUuid").value(product.getCategory().getUuid().toString()))
                .andExpect(jsonPath("$.categoryName").value("Bomboniere"))

                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.barcode").doesNotExist())
                .andExpect(jsonPath("$.unitOfMeasure").doesNotExist());
    }

	@Test
	@DisplayName("Should return HTTP 409 Conflict when adding a product a existent product")
	void shouldReturnConflictWhenEntityAlreadyExists() throws Exception{
		String errorMessage = "Produto já cadastrado.";
		String requestURI = "/api/product";

		Category category = new Category(1L, "Bomboniere");

		Product product = new Product("Chocolate Classic ao Leite", category, "1112223334445", "Barra de chocolate ao leite 80g", MeasurementType.UNIT);

		ProductRequest requestDTO = ProductRequest.convertToDTO(product);

        when(service.createProduct(any()))
        		.thenThrow(new EntityExistsException(errorMessage));

        mockMvc.perform(post("/api/product")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
                .andExpect(status().isConflict())

                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Registro já cadastrado."))
                .andExpect(jsonPath("$.message").value("Produto já cadastrado."))
                .andExpect(jsonPath("$.path").value(requestURI))
                .andExpect(jsonPath("$.timestamp").exists());

	}

	@Test
	@DisplayName("Should find a product by uuid in the URL and never expose the internal id")
	void shouldFindProductByUuid() throws Exception{
		Product product = newProduct();

		when(service.findByUuid(product.getUuid())).thenReturn(product);

		mockMvc.perform(get("/api/product/{productUuid}", product.getUuid()))
				.andExpect(status().isOk())

				.andExpect(jsonPath("$.uuid").value(product.getUuid().toString()))
				.andExpect(jsonPath("$.name").value("Bombom Alpino"))
				.andExpect(jsonPath("$.id").doesNotExist());
	}

	@Test
	@DisplayName("Should list products in a page without the internal id")
	void shouldListProductsWithoutId() throws Exception{
		Product product = newProduct();

		when(service.findAll(any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(product), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/product"))
				.andExpect(status().isOk())

				.andExpect(jsonPath("$.content[0].uuid").value(product.getUuid().toString()))
				.andExpect(jsonPath("$.content[0].id").doesNotExist())

				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.totalPages").value(1))
				.andExpect(jsonPath("$.first").value(true))
				.andExpect(jsonPath("$.last").value(true));
	}

	@Test
	@DisplayName("Should describe the position of the page in the response")
	void shouldReturnPageMetadata() throws Exception{
		Product product = newProduct();

		when(service.findAll(any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(product), PageRequest.of(2, 5), 53));

		mockMvc.perform(get("/api/product").param("page", "2").param("size", "5"))
				.andExpect(status().isOk())

				.andExpect(jsonPath("$.page").value(2))
				.andExpect(jsonPath("$.size").value(5))
				.andExpect(jsonPath("$.totalElements").value(53))
				.andExpect(jsonPath("$.totalPages").value(11))
				.andExpect(jsonPath("$.first").value(false))
				.andExpect(jsonPath("$.last").value(false));
	}

	@Test
	@DisplayName("Should send page, size and sort from the URL to the service")
	void shouldPassPagingParametersToService() throws Exception{
		when(service.findAll(any(Pageable.class))).thenReturn(Page.empty());

		mockMvc.perform(get("/api/product").param("page", "2").param("size", "5").param("sort", "name,desc"))
				.andExpect(status().isOk());

		Pageable pageable = capturePageable();
		assertEquals(2, pageable.getPageNumber());
		assertEquals(5, pageable.getPageSize());
		assertEquals(Sort.by(Sort.Order.desc("name")), pageable.getSort());
	}

	@Test
	@DisplayName("Should list 20 products ordered by name when no paging parameters are sent")
	void shouldUseDefaultPaging() throws Exception{
		when(service.findAll(any(Pageable.class))).thenReturn(Page.empty());

		mockMvc.perform(get("/api/product"))
				.andExpect(status().isOk());

		Pageable pageable = capturePageable();
		assertEquals(0, pageable.getPageNumber());
		assertEquals(20, pageable.getPageSize());
		assertEquals(Sort.by(Sort.Order.asc("name")), pageable.getSort());
	}

	@Test
	@DisplayName("Should cap the page size at 100 instead of listing everything")
	void shouldCapPageSize() throws Exception{
		when(service.findAll(any(Pageable.class))).thenReturn(Page.empty());

		mockMvc.perform(get("/api/product").param("size", "5000"))
				.andExpect(status().isOk());

		assertEquals(100, capturePageable().getPageSize());
	}

	@Test
	@DisplayName("Should return HTTP 400 when sorting by a field that does not exist")
	void shouldRejectInvalidSortField() throws Exception{
		when(service.findAll(any(Pageable.class)))
				.thenThrow(new InvalidSortException("foo", List.of("name", "barcode")));

		mockMvc.perform(get("/api/product").param("sort", "foo"))
				.andExpect(status().isBadRequest())

				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.error").value("Ordenação inválida."))
				.andExpect(jsonPath("$.message").value("Não é possível ordenar por 'foo'. Campos permitidos: name, barcode."))
				.andExpect(jsonPath("$.path").value("/api/product"));
	}

	// Pega o Pageable que o controller montou a partir da URL e entregou ao service.
	private Pageable capturePageable() {
		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(service).findAll(captor.capture());
		return captor.getValue();
	}

	@Test
	@DisplayName("Should return HTTP 404 when no product has the uuid")
	void shouldReturnNotFoundWhenUuidDoesNotExist() throws Exception{
		UUID uuid = UUID.randomUUID();

		when(service.findByUuid(uuid))
				.thenThrow(new EntityNotFoundException("Produto com uuid: " + uuid + " não encontrado."));

		mockMvc.perform(get("/api/product/{productUuid}", uuid))
				.andExpect(status().isNotFound())

				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.path").value("/api/product/" + uuid));
	}

	@Test
	@DisplayName("Should return HTTP 400 when the URL carries a numeric id instead of a uuid")
	void shouldRejectNumericIdInTheUrl() throws Exception{
		mockMvc.perform(get("/api/product/1"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Should update a product by uuid and never expose the internal id")
	void shouldUpdateProductByUuid() throws Exception{
		Product product = newProduct();
		ProductRequest requestDTO = ProductRequest.convertToDTO(product);

		when(service.updateProduct(eq(product.getUuid()), any(ProductRequest.class))).thenReturn(product);

		mockMvc.perform(put("/api/product/{productUuid}", product.getUuid())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(requestDTO)))
				.andExpect(status().isOk())

				.andExpect(jsonPath("$.uuid").value(product.getUuid().toString()))
				.andExpect(jsonPath("$.id").doesNotExist());
	}

	@Test
	@DisplayName("Should delete a product by uuid and return HTTP 204")
	void shouldDeleteProductByUuid() throws Exception{
		UUID uuid = UUID.randomUUID();

		mockMvc.perform(delete("/api/product/{productUuid}", uuid))
				.andExpect(status().isNoContent());

		verify(service).deleteProduct(uuid);
	}
}
