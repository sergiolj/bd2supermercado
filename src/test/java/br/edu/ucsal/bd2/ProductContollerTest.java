package br.edu.ucsal.bd2;

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
import br.edu.ucsal.bd2.model.Brand;
import br.edu.ucsal.bd2.model.FoodCategory;
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
		Brand brand = new Brand(1L, "Nestlé");
		FoodCategory category = new FoodCategory(1L, "Bomboniere");
		
		Product product = new Product(1L, "Bombom Alpino", brand, "1112223334445", MeasurementType.GRAM, 100.0, category);
				
		ProductRequest requestDTO = ProductRequest.convertToDTO(product);

        when(service.createProduct(any(ProductRequest.class))).thenReturn(product);

        mockMvc.perform(post("/api/product")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
                .andExpect(status().isCreated())
                
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("Bombom Alpino"))
                .andExpect(jsonPath("$.weight").value(100.0))
                .andExpect(jsonPath("$.brandName").value("Nestlé"))
                
                .andExpect(jsonPath("$.barCode").doesNotExist())
                .andExpect(jsonPath("$.unitType").doesNotExist());   
    }
	
	@Test
	@DisplayName("Should return HTTP 409 Conflict when adding a product a existent product")
	void shouldReturnConflictWhenEntityAlreadyExists() throws Exception{
		String errorMessage = "Produto já cadastrado.";
		String requestURI = "/api/product";
		
		Brand brand = new Brand(1L, "Nestlé");
		FoodCategory category = new FoodCategory(1L, "Bomboniere");
		
		Product product = new Product(1L, "Chocolate Classic ao Leite", brand, "1112223334445", MeasurementType.GRAM, 80.0, category);
		
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
