package br.edu.ucsal.bd2.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

// Endpoints das 3 consultas com Criteria API (Ref. §4.2 item 5). Todos os filtros são opcionais.
// Datas no formato ISO (2026-01-31). Valores em centavos. Vendas e faturamento aceitam o header X-Store-Id para olhar
// uma loja só; sem ele, valem para a rede inteira.
@Tag(name = "Consultas (Criteria API)", description = "Consultas de leitura montadas com a Criteria API da JPA. "
		+ "Todos os filtros são opcionais; datas no formato ISO (2026-01-31) e valores em centavos. Vendas e faturamento "
		+ "olham a loja do header X-Store-Id, ou a rede inteira sem ele.")
@RestController
@RequestMapping("/api/criteria")
public class CriteriaQueryController {

	private final CriteriaQueryService service;

	public CriteriaQueryController(CriteriaQueryService service) {
		this.service = service;
	}

	// GET /api/criteria/products?name=arroz&categoryUuid=...&minPriceInCents=500&maxPriceInCents=2000&unitOfMeasure=UNIT
	@Operation(summary = "Criteria 1: busca de produtos com filtros dinâmicos e preço vigente",
			description = "Só entram produtos ativos com preço vigente. `name` busca por trecho, sem diferenciar maiúsculas. "
					+ "Ordenado por nome; `page` começa em 0 e `size` vai até 100 (padrão 20).")
	@ApiResponse(responseCode = "200", description = "Página de produtos")
	@ApiResponse(responseCode = "400", description = "`minPriceInCents` maior que `maxPriceInCents`, ou `page` negativo")
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
	@Operation(summary = "Criteria 2: busca de vendas com filtros dinâmicos (loja, período, vendedor, status, valor)",
			description = "`from` e `to` são inclusivos e contam dias do fuso America/Bahia. Mais recentes primeiro; "
					+ "`page` começa em 0 e `size` vai até 100 (padrão 20).")
	@ApiResponse(responseCode = "200", description = "Página de vendas")
	@ApiResponse(responseCode = "400", description = "`from` depois de `to`, `minTotalInCents` maior que `maxTotalInCents`, ou `page` negativo")
	@ApiResponse(responseCode = "404", description = "Loja do header inexistente ou apagada")
	@GetMapping("/sales")
	public ResponseEntity<PageResponse<SaleSummaryResponse>> searchSales(
			@Parameter(description = StoreHeader.OPTIONAL) @RequestHeader(value = StoreHeader.NAME, required = false) UUID storeUuid,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID sellerUuid,
			@RequestParam(required = false) SaleStatus status,
			@RequestParam(required = false) Long minTotalInCents,
			@RequestParam(required = false) Long maxTotalInCents,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		SaleSearchFilter filter = new SaleSearchFilter(storeUuid, from, to, sellerUuid, status, minTotalInCents, maxTotalInCents);
		return ResponseEntity.ok(PageResponse.from(service.searchSales(filter, page, size), Function.identity()));
	}

	// GET /api/criteria/revenue-by-category?from=2026-01-01&to=2026-03-31&minRevenueInCents=100000
	@Operation(summary = "Criteria 3: faturamento por categoria no período (GROUP BY / HAVING)",
			description = "Só vendas COMPLETED contam como faturamento. `minRevenueInCents` esconde as categorias abaixo desse "
					+ "valor. Ordenado do maior faturamento para o menor.")
	@ApiResponse(responseCode = "200", description = "Faturamento por categoria")
	@ApiResponse(responseCode = "400", description = "`from` depois de `to`")
	@ApiResponse(responseCode = "404", description = "Loja do header inexistente ou apagada")
	@GetMapping("/revenue-by-category")
	public ResponseEntity<List<CategoryRevenueResponse>> revenueByCategory(
			@Parameter(description = StoreHeader.OPTIONAL) @RequestHeader(value = StoreHeader.NAME, required = false) UUID storeUuid,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long minRevenueInCents) {

		return ResponseEntity.ok(service.revenueByCategory(storeUuid, from, to, minRevenueInCents));
	}
}
