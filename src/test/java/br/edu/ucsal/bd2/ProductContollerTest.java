package br.edu.ucsal.bd2;

import java.util.List;
import java.util.UUID;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.edu.ucsal.bd2.controller.ProductController;
import br.edu.ucsal.bd2.dto.request.ProductRequest;
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
                .andExpect(jsonPath("$.error").value(errorMessage))
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
	@DisplayName("Should list products without the internal id")
	void shouldListProductsWithoutId() throws Exception{
		Product product = newProduct();

		when(service.findAll()).thenReturn(List.of(product));

		mockMvc.perform(get("/api/product"))
				.andExpect(status().isOk())

				.andExpect(jsonPath("$[0].uuid").value(product.getUuid().toString()))
				.andExpect(jsonPath("$[0].id").doesNotExist());
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
