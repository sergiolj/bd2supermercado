package br.edu.ucsal.bd2.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.dto.filter.ProductSearchFilter;
import br.edu.ucsal.bd2.dto.filter.SaleSearchFilter;
import br.edu.ucsal.bd2.dto.response.CategoryRevenueResponse;
import br.edu.ucsal.bd2.dto.response.PageResponse;
import br.edu.ucsal.bd2.dto.response.ProductSearchResponse;
import br.edu.ucsal.bd2.dto.response.SaleSummaryResponse;
import br.edu.ucsal.bd2.service.CriteriaQueryService;
import br.edu.ucsal.bd2.util.MeasurementType;
import br.edu.ucsal.bd2.util.SaleStatus;
import io.swagger.v3.oas.annotations.Operation;

// Endpoints das 3 consultas com Criteria API (Ref. §4.2 item 5). Todos os filtros são opcionais.
// Datas no formato ISO (2026-01-31). Valores em centavos.
@RestController
@RequestMapping("/api/criteria")
public class CriteriaQueryController {

	private final CriteriaQueryService service;

	public CriteriaQueryController(CriteriaQueryService service) {
		this.service = service;
	}

	// GET /api/criteria/products?name=arroz&categoryUuid=...&minPriceInCents=500&maxPriceInCents=2000&unitOfMeasure=UNIT
	@Operation(summary = "Criteria 1: busca de produtos com filtros dinâmicos e preço vigente")
	@GetMapping("/products")
	public ResponseEntity<PageResponse<ProductSearchResponse>> searchProducts(
			@RequestParam(required = false) String name,
			@RequestParam(required = false) UUID categoryUuid,
			@RequestParam(required = false) Long minPriceInCents,
			@RequestParam(required = false) Long maxPriceInCents,
			@RequestParam(required = false) MeasurementType unitOfMeasure,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		ProductSearchFilter filter = new ProductSearchFilter(name, categoryUuid, minPriceInCents, maxPriceInCents, unitOfMeasure);
		return ResponseEntity.ok(PageResponse.from(service.searchProducts(filter, page, size), Function.identity()));
	}

	// GET /api/criteria/sales?from=2026-01-01&to=2026-01-31&status=COMPLETED&minTotalInCents=10000
	@Operation(summary = "Criteria 2: busca de vendas com filtros dinâmicos (período, vendedor, status, valor)")
	@GetMapping("/sales")
	public ResponseEntity<PageResponse<SaleSummaryResponse>> searchSales(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID sellerUuid,
			@RequestParam(required = false) SaleStatus status,
			@RequestParam(required = false) Long minTotalInCents,
			@RequestParam(required = false) Long maxTotalInCents,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		SaleSearchFilter filter = new SaleSearchFilter(from, to, sellerUuid, status, minTotalInCents, maxTotalInCents);
		return ResponseEntity.ok(PageResponse.from(service.searchSales(filter, page, size), Function.identity()));
	}

	// GET /api/criteria/revenue-by-category?from=2026-01-01&to=2026-03-31&minRevenueInCents=100000
	@Operation(summary = "Criteria 3: faturamento por categoria no período (GROUP BY / HAVING)")
	@GetMapping("/revenue-by-category")
	public ResponseEntity<List<CategoryRevenueResponse>> revenueByCategory(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long minRevenueInCents) {

		return ResponseEntity.ok(service.revenueByCategory(from, to, minRevenueInCents));
	}
}
