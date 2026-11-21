package br.edu.ucsal.bd2.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.dao.JpqlQueryDAO;
import br.edu.ucsal.bd2.dto.response.CustomerSpendingResponse;
import br.edu.ucsal.bd2.dto.response.ProductPriceResponse;
import br.edu.ucsal.bd2.dto.response.ProductSalesResponse;
import br.edu.ucsal.bd2.dto.response.StoreRevenueResponse;
import io.swagger.v3.oas.annotations.Operation;

// Consultas de relatório em JPQL (Ref. §4.2 item 4). Valores em centavos; só vendas COMPLETED contam.
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
}

