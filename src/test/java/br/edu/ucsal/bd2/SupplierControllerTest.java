package br.edu.ucsal.bd2;

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

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.edu.ucsal.bd2.controller.SupplierController;
import br.edu.ucsal.bd2.dto.request.SupplierRequest;
import br.edu.ucsal.bd2.model.Supplier;
import br.edu.ucsal.bd2.service.SupplierService;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

@WebMvcTest(SupplierController.class)
class SupplierControllerTest {

	@Autowired
	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@MockitoBean
	private SupplierService service;

	// id interno preenchido de propósito: os testes garantem que ele NUNCA aparece na resposta.
	private Supplier newSupplier() {
		Supplier supplier = new Supplier();
		supplier.setId(1L);
		supplier.setUuid(UUID.randomUUID());
		supplier.setName("Distribuidora Bahia Alimentos");
		supplier.setTaxNumber("12345678000195");
		supplier.setEmail("vendas@bahia.com.br");
		supplier.setPhone("(71) 3333-4444");
		return supplier;
	}

	@Test
	@DisplayName("Should return HTTP 201 Created when adding a supplier")
	void shouldCreateSupplier() throws Exception {
		Supplier supplier = newSupplier();
		when(service.createSupplier(any(SupplierRequest.class))).thenReturn(supplier);

		mockMvc.perform(post("/api/supplier")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(SupplierRequest.convertToDTO(supplier))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.uuid").value(supplier.getUuid().toString()))
				.andExpect(jsonPath("$.name").value("Distribuidora Bahia Alimentos"))
				.andExpect(jsonPath("$.taxNumber").value("12345678000195"))
				.andExpect(jsonPath("$.id").doesNotExist());
	}

	@Test
	@DisplayName("Should return HTTP 409 Conflict for a duplicated CNPJ")
	void shouldReturnConflict() throws Exception {
		when(service.createSupplier(any())).thenThrow(new EntityExistsException("Fornecedor já existe com esse CNPJ."));

		mockMvc.perform(post("/api/supplier")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new SupplierRequest("X", "12345678000195", null, null))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Fornecedor já existe com esse CNPJ."));
	}

	@Test
	@DisplayName("Should list suppliers in a page without the internal id")
	void shouldListSuppliers() throws Exception {
		Supplier supplier = newSupplier();
		when(service.findAll(any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(supplier), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/supplier"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].uuid").value(supplier.getUuid().toString()))
				.andExpect(jsonPath("$.content[0].id").doesNotExist())
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	@DisplayName("Should return HTTP 404 for an unknown supplier")
	void shouldReturnNotFound() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(service.findByUuid(uuid)).thenThrow(new EntityNotFoundException("Fornecedor com uuid: " + uuid + " não encontrado."));

		mockMvc.perform(get("/api/supplier/{supplierUuid}", uuid))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Should update and delete a supplier by uuid")
	void shouldUpdateAndDeleteSupplier() throws Exception {
		Supplier supplier = newSupplier();
		when(service.updateSupplier(eq(supplier.getUuid()), any(SupplierRequest.class))).thenReturn(supplier);

		mockMvc.perform(put("/api/supplier/{supplierUuid}", supplier.getUuid())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(SupplierRequest.convertToDTO(supplier))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Distribuidora Bahia Alimentos"));

		mockMvc.perform(delete("/api/supplier/{supplierUuid}", supplier.getUuid()))
				.andExpect(status().isNoContent());
		verify(service).deleteSupplier(supplier.getUuid());
	}
}
