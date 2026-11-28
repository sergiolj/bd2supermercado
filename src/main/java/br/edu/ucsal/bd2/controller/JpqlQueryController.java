package br.edu.ucsal.bd2.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.JpqlQueryDAO;
import br.edu.ucsal.bd2.dto.response.CategoryMonthlyRevenueResponse;
import br.edu.ucsal.bd2.dto.response.CategoryStockFlowResponse;
import br.edu.ucsal.bd2.dto.response.CustomerSpendingResponse;
import br.edu.ucsal.bd2.dto.response.InactiveCustomerResponse;
import br.edu.ucsal.bd2.dto.response.PaymentMixResponse;
import br.edu.ucsal.bd2.dto.response.ProductPriceResponse;
import br.edu.ucsal.bd2.dto.response.ProductSalesResponse;
import br.edu.ucsal.bd2.dto.response.PurchaseHistoryResponse;
import br.edu.ucsal.bd2.dto.response.SellerPerformanceResponse;
import br.edu.ucsal.bd2.dto.response.StoreRevenueResponse;
import br.edu.ucsal.bd2.service.CriteriaQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

// Consultas de relatório em JPQL (Ref. §4.2 item 4). Valores em centavos; só vendas COMPLETED contam.
// Períodos: from e to são dias do calendário da loja, os dois inclusive. Sem eles, valem os últimos 12 meses.
@RestController
@RequestMapping("/api/jpql")
public class JpqlQueryController {

    private final JpqlQueryDAO dao;

    public JpqlQueryController(JpqlQueryDAO dao) {
        this.dao = dao;
    }

    @Operation(summary = "JPQL 1 (@NamedQuery): total gasto por cliente, com HAVING e paginação")
    @GetMapping("/customers/spending")
    public ResponseEntity<List<CustomerSpendingResponse>> totalByCustomer(
            @RequestParam(defaultValue = "0") long minTotalInCents,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            page = 0;
        }
        if (size < 1) {
            size = 1;
        }
        if (size > 100) {
            size = 100;
        }

        return ResponseEntity.ok(dao.totalByCustomer(minTotalInCents, page, size));
    }

    @Operation(summary = "JPQL 2 (@NamedQuery): faturamento por loja")
    @GetMapping("/stores/revenue")
    public ResponseEntity<List<StoreRevenueResponse>> revenueByStore() {
        return ResponseEntity.ok(dao.revenueByStore());
    }

    @Operation(summary = "JPQL 3 (subconsulta): produtos com preço acima da média")
    @GetMapping("/products/above-average-price")
    public ResponseEntity<List<ProductPriceResponse>> productsAboveAveragePrice() {
        return ResponseEntity.ok(dao.productsAboveAveragePrice());
    }

    @Operation(summary = "JPQL 4 (junções): produtos mais vendidos de uma loja")
    @GetMapping("/stores/{storeUuid}/top-products")
    public ResponseEntity<List<ProductSalesResponse>> topProductsByStore(
            @PathVariable UUID storeUuid,
            @RequestParam(defaultValue = "10") int limit) {
        if (limit < 1) {
            limit = 1;
        }
        if (limit > 50) {
            limit = 50;
        }

        return ResponseEntity.ok(dao.topProductsByStore(storeUuid, limit));
    }

    @Operation(summary = "JPQL 5 (EXTRACT + GROUP BY): faturamento por categoria e mês")
    @GetMapping("/categories/monthly-revenue")
    public ResponseEntity<List<CategoryMonthlyRevenueResponse>> monthlyRevenueByCategory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period period = Period.of(from, to);
        return ResponseEntity.ok(dao.monthlyRevenueByCategory(period.start(), period.end()));
    }

    @Operation(summary = "JPQL 6 (JOIN FETCH + paginação): histórico de compras de um cliente, com os itens")
    @GetMapping("/customers/{customerUuid}/purchases")
    public ResponseEntity<List<PurchaseHistoryResponse>> purchaseHistory(
            @PathVariable UUID customerUuid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(dao.purchaseHistory(customerUuid, Math.max(page, 0), clamp(size, 1, 50)));
    }

    @Operation(summary = "JPQL 7 (herança JOINED): desempenho dos vendedores, com ticket médio")
    @GetMapping("/sellers/performance")
    public ResponseEntity<List<SellerPerformanceResponse>> sellerPerformance(
            @Parameter(description = StoreHeader.OPTIONAL) @RequestHeader(value = StoreHeader.NAME, required = false) UUID storeUuid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period period = Period.of(from, to);
        return ResponseEntity.ok(dao.sellerPerformance(storeUuid, period.start(), period.end()));
    }

    @Operation(summary = "JPQL 8 (subconsulta NOT EXISTS + paginação): clientes sem compra desde uma data (padrão: 90 dias)")
    @GetMapping("/customers/inactive")
    public ResponseEntity<List<InactiveCustomerResponse>> inactiveCustomers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDate day = since != null ? since : today().minusDays(90);
        return ResponseEntity.ok(dao.inactiveCustomers(startOfDay(day), Math.max(page, 0), clamp(size, 1, 100)));
    }

    @Operation(summary = "JPQL 9 (CASE WHEN): entradas, vendas e perdas de estoque por categoria na loja")
    @GetMapping("/stock/flow-by-category")
    public ResponseEntity<List<CategoryStockFlowResponse>> stockFlowByCategory(
            @Parameter(description = StoreHeader.REQUIRED) @RequestHeader(StoreHeader.NAME) UUID storeUuid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period period = Period.of(from, to);
        return ResponseEntity.ok(dao.stockFlowByCategory(storeUuid, period.start(), period.end()));
    }

    @Operation(summary = "JPQL 10 (caminho encadeado + BETWEEN): recebimentos por loja e forma de pagamento")
    @GetMapping("/payments/mix-by-store")
    public ResponseEntity<List<PaymentMixResponse>> paymentMixByStore(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period period = Period.of(from, to);
        // BETWEEN inclui as duas pontas: o fim é o último instante antes do dia seguinte.
        return ResponseEntity.ok(dao.paymentMixByStore(period.start(), period.end().minusNanos(1000)));
    }

    // ---------------------------------------------------------------------------------------------

    // Período [start, end) em instantes, a partir de dias do calendário da loja (to inclusive).
    private record Period(Instant start, Instant end) {

        static Period of(LocalDate from, LocalDate to) {
            LocalDate last = to != null ? to : today();
            LocalDate first = from != null ? from : last.minusMonths(12).plusDays(1);
            if (first.isAfter(last)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from não pode ser depois de to");
            }
            return new Period(startOfDay(first), startOfDay(last.plusDays(1)));
        }
    }

    private static LocalDate today() {
        return LocalDate.now(CriteriaQueryService.STORE_ZONE);
    }

    private static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(CriteriaQueryService.STORE_ZONE).toInstant();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
