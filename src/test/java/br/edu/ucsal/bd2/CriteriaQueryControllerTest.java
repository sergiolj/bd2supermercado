package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.controller.CriteriaQueryController;
import br.edu.ucsal.bd2.dto.filter.ProductSearchFilter;
import br.edu.ucsal.bd2.dto.filter.SaleSearchFilter;
import br.edu.ucsal.bd2.dto.response.CategoryRevenueResponse;
import br.edu.ucsal.bd2.dto.response.ProductSearchResponse;
import br.edu.ucsal.bd2.dto.response.SaleSummaryResponse;
import br.edu.ucsal.bd2.service.CriteriaQueryService;
import br.edu.ucsal.bd2.util.MeasurementType;
import br.edu.ucsal.bd2.util.SaleStatus;

// Testa só a camada web: os parâmetros da URL viram o filtro certo e a resposta sai no formato esperado.
// As consultas em si são testadas contra o PostgreSQL em CriteriaQueryServiceIntegrationTest.
@WebMvcTest(CriteriaQueryController.class)
class CriteriaQueryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CriteriaQueryService service;

	@Test
	@DisplayName("Should turn the URL parameters into a product filter and return a page")
	void shouldSearchProductsWithFilters() throws Exception {
		UUID categoryUuid = UUID.randomUUID();
		ProductSearchResponse row = new ProductSearchResponse(UUID.randomUUID(), "Arroz Tipo 1 5kg", "7890000000001",
				MeasurementType.UNIT, "Mercearia", 2899L);

		when(service.searchProducts(any(ProductSearchFilter.class), anyInt(), anyInt()))
				.thenReturn(new PageImpl<>(List.of(row), PageRequest.of(1, 10), 11));

		mockMvc.perform(get("/api/criteria/products")
				.param("name", "arroz")
				.param("categoryUuid", categoryUuid.toString())
				.param("minPriceInCents", "1000")
				.param("maxPriceInCents", "5000")
				.param("unitOfMeasure", "UNIT")
				.param("page", "1")
				.param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].name").value("Arroz Tipo 1 5kg"))
				.andExpect(jsonPath("$.content[0].currentPriceInCents").value(2899))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.totalElements").value(11));

		ArgumentCaptor<ProductSearchFilter> captor = ArgumentCaptor.forClass(ProductSearchFilter.class);
		verify(service).searchProducts(captor.capture(), eq(1), eq(10));
		assertEquals(new ProductSearchFilter("arroz", categoryUuid, 1000L, 5000L, MeasurementType.UNIT), captor.getValue());
	}

	@Test
	@DisplayName("Should accept a product search without any filter")
	void shouldSearchProductsWithoutFilters() throws Exception {
		when(service.searchProducts(any(ProductSearchFilter.class), anyInt(), anyInt()))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

		mockMvc.perform(get("/api/criteria/products"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty());

		verify(service).searchProducts(eq(new ProductSearchFilter(null, null, null, null, null)), eq(0), eq(20));
	}

	@Test
	@DisplayName("Should parse ISO dates and status when searching sales")
	void shouldSearchSalesWithFilters() throws Exception {
		SaleSummaryResponse row = new SaleSummaryResponse(UUID.randomUUID(), Instant.parse("2026-01-15T13:00:00Z"),
				SaleStatus.COMPLETED, 15990L, "Maria Souza");

		when(service.searchSales(any(SaleSearchFilter.class), anyInt(), anyInt()))
				.thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/criteria/sales")
				.param("from", "2026-01-01")
				.param("to", "2026-01-31")
				.param("status", "COMPLETED")
				.param("minTotalInCents", "10000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].sellerName").value("Maria Souza"))
				.andExpect(jsonPath("$.content[0].status").value("COMPLETED"));

		verify(service).searchSales(eq(new SaleSearchFilter(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
				null, SaleStatus.COMPLETED, 10000L, null)), eq(0), eq(20));
	}

	@Test
	@DisplayName("Should return 400 when the service rejects the filter")
	void shouldReturnBadRequestForInvalidRange() throws Exception {
		when(service.searchSales(any(SaleSearchFilter.class), anyInt(), anyInt()))
				.thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "from não pode ser maior que to."));

		mockMvc.perform(get("/api/criteria/sales")
				.param("from", "2026-02-01")
				.param("to", "2026-01-01"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Should return the revenue per category list")
	void shouldReturnRevenueByCategory() throws Exception {
		when(service.revenueByCategory(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 100000L))
				.thenReturn(List.of(new CategoryRevenueResponse("Bebidas", 320L, new BigDecimal("812.000"), 1250000L)));

		mockMvc.perform(get("/api/criteria/revenue-by-category")
				.param("from", "2026-01-01")
				.param("to", "2026-03-31")
				.param("minRevenueInCents", "100000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].categoryName").value("Bebidas"))
				.andExpect(jsonPath("$[0].salesCount").value(320))
				.andExpect(jsonPath("$[0].revenueInCents").value(1250000));
	}
}
