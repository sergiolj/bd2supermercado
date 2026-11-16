package br.edu.ucsal.bd2;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

@WebMvcTest(ProductController.class)
class ProductContollerTest {
	
	@Autowired
	private MockMvc mockMvc;
	
	private final ObjectMapper objectMapper = new ObjectMapper();
	
	@MockitoBean
	private ProductService service;
	

	@Test
	@DisplayName("Should return HPPT 201 Created and Location header when adding a product")
	void shouldCreateProduct() throws Exception{
		Category category = new Category(1L, "Bomboniere");

		Product product = new Product("Bombom Alpino", category, "1112223334445", "Bombom de chocolate com recheio", MeasurementType.UNIT);
		product.setId(1L);
		product.setUuid(UUID.randomUUID());

		ProductRequest requestDTO = ProductRequest.convertToDTO(product);

        when(service.createProduct(any(ProductRequest.class))).thenReturn(product);

        mockMvc.perform(post("/api/product")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
                .andExpect(status().isCreated())

                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.uuid").value(product.getUuid().toString()))
                .andExpect(jsonPath("$.name").value("Bombom Alpino"))
                .andExpect(jsonPath("$.description").value("Bombom de chocolate com recheio"))
                .andExpect(jsonPath("$.categoryName").value("Bomboniere"))

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
}
