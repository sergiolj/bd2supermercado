package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dto.request.PurchaseRequest;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Supplier;
import br.edu.ucsal.bd2.service.PurchaseService;
import br.edu.ucsal.bd2.util.MeasurementType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;

// Roda o lançamento e o estorno de compras de verdade no PostgreSQL do .env, com o trigger que mantém o estoque.
// Estes testes NÃO usam @Transactional: o estorno recusado precisa do rollback real do service. Por isso cada teste
// grava os próprios dados (nomes únicos) e o @AfterEach apaga tudo; o resto do banco (inclusive o seed) não é tocado.
@SpringBootTest
class PurchaseServiceIntegrationTest {

    @Autowired
    private PurchaseService service;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    private final String tag = "ZZBUY" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private Long categoryId;
    private Long supplierId;
    private Long riceId;
    private Long beansId;
    private UUID riceUuid;
    private UUID beansUuid;
    private UUID supplierUuid;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> {
            Category category = new Category();
            category.setName(tag + " Categoria");
            em.persist(category);
            categoryId = category.getId();

            Supplier supplier = new Supplier();
            supplier.setName(tag + " Fornecedor");
            supplier.setTaxNumber("S" + tag);
            em.persist(supplier);
            supplierId = supplier.getId();
            supplierUuid = supplier.getUuid();

            Product rice = new Product(tag + " Arroz", category, "R" + tag, null, MeasurementType.UNIT);
            Product beans = new Product(tag + " Feijao", category, "F" + tag, null, MeasurementType.KG);
            em.persist(rice);
            em.persist(beans);
            riceId = rice.getId();
            beansId = beans.getId();
            riceUuid = rice.getUuid();
            beansUuid = beans.getUuid();
        });
    }

    @AfterEach
    void cleanUp() {
        // As saídas (SALE) saem antes das entradas: o trigger desfaz cada movimentação removida, e remover a compra
        // primeiro deixaria o estoque negativo (o CHECK recusaria).
        jdbc.update("DELETE FROM inventory_movement WHERE type <> 'PURCHASE' AND product_id IN (?, ?)", riceId, beansId);
        jdbc.update("DELETE FROM inventory_movement WHERE product_id IN (?, ?)", riceId, beansId);
        jdbc.update("DELETE FROM inventory WHERE product_id IN (?, ?)", riceId, beansId);
        jdbc.update("DELETE FROM product WHERE id IN (?, ?)", riceId, beansId);
        jdbc.update("DELETE FROM supplier WHERE id = ?", supplierId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    private InventoryMovement buy(UUID productUuid, String quantity, long unitCost) {
        return service.registerPurchase(new PurchaseRequest(productUuid, supplierUuid, new BigDecimal(quantity), unitCost, null));
    }

    private BigDecimal stock(Long productId) {
        List<BigDecimal> rows = jdbc.queryForList("SELECT total_quantity FROM inventory WHERE product_id = ?", BigDecimal.class, productId);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    private void assertStock(String expected, Long productId) {
        assertEquals(0, new BigDecimal(expected).compareTo(stock(productId)), "estoque: " + stock(productId));
    }

    // Uma saída de estoque (venda/perda) gravada direto, como o seed faz: o trigger baixa o saldo.
    private UUID consume(Long productId, String type, String quantity) {
        return jdbc.queryForObject("INSERT INTO inventory_movement (product_id, type, quantity, created_at) VALUES (?, ?, ?, now()) RETURNING uuid",
                UUID.class, productId, type, new BigDecimal(quantity));
    }

    @Test
    @DisplayName("O lançamento da compra soma ao estoque pelo trigger e grava uuid, tipo PURCHASE, custo e validade")
    void shouldRegisterPurchaseAndIncreaseStock() {
        LocalDate expiresAt = LocalDate.now().plusMonths(3);

        InventoryMovement purchase = service.registerPurchase(new PurchaseRequest(riceUuid, supplierUuid, new BigDecimal("24"), 1850L, expiresAt));

        assertNotNull(purchase.getUuid());
        assertNotNull(purchase.getCreatedAt());
        assertStock("24", riceId);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inventory_movement WHERE uuid = ? AND type = 'PURCHASE' "
                + "AND unit_cost_in_cents = 1850 AND batch_expires_at = ? AND supplier_id = ?", Integer.class,
                purchase.getUuid(), java.sql.Date.valueOf(expiresAt), supplierId));

        InventoryMovement found = service.findByUuid(purchase.getUuid());
        assertEquals(riceUuid, found.getProduct().getUuid());
        assertEquals(supplierUuid, found.getSupplier().getUuid());
        assertEquals(0, new BigDecimal("24").compareTo(found.getQuantity()));
    }

    @Test
    @DisplayName("Compras acumulam no estoque (inclusive fracionadas) e apagar uma tira exatamente a quantidade dela")
    void shouldAccumulateAndReverseExactQuantity() {
        InventoryMovement first = buy(riceUuid, "10", 1000L);
        InventoryMovement second = buy(riceUuid, "5", 1100L);
        buy(beansUuid, "12.500", 899L);

        assertStock("15", riceId);
        assertStock("12.5", beansId);

        service.deletePurchase(first.getUuid());

        assertStock("5", riceId);
        assertStock("12.5", beansId); // outro produto não é afetado
        assertThrows(EntityNotFoundException.class, () -> service.findByUuid(first.getUuid()));
        assertEquals(second.getUuid(), service.findByUuid(second.getUuid()).getUuid());
    }

    @Test
    @DisplayName("Recusa o estorno com 409 quando parte da quantidade já saiu, e deixa tudo como estava")
    void shouldRefuseReversalWhenStockWasConsumed() {
        InventoryMovement big = buy(riceUuid, "10", 1000L);
        InventoryMovement small = buy(riceUuid, "5", 1000L);
        consume(riceId, "SALE", "7"); // estoque: 15 - 7 = 8
        assertStock("8", riceId);

        // Apagar a de 10 deixaria o estoque em -2: recusado, e a compra continua lá.
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.deletePurchase(big.getUuid()));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains(tag + " Arroz"), error.getReason());
        assertEquals(big.getUuid(), service.findByUuid(big.getUuid()).getUuid());
        assertStock("8", riceId);

        // Apagar a de 5 cabe no que sobrou (8 - 5 = 3): permitido.
        service.deletePurchase(small.getUuid());
        assertStock("3", riceId);
    }

    @Test
    @DisplayName("Só enxerga compras: o uuid de uma venda ou perda dá 404 e não pode ser apagado por aqui")
    void shouldOnlyExposePurchases() {
        buy(riceUuid, "10", 1000L);
        UUID sale = consume(riceId, "SALE", "2");
        UUID loss = consume(riceId, "LOSS", "1");

        for (UUID other : List.of(sale, loss)) {
            assertThrows(EntityNotFoundException.class, () -> service.findByUuid(other));
            assertThrows(EntityNotFoundException.class, () -> service.deletePurchase(other));
        }
        assertStock("7", riceId); // nada foi desfeito
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM inventory_movement WHERE type <> 'PURCHASE' AND product_id = ?", Integer.class, riceId));
    }

    @Test
    @DisplayName("Lista as compras da mais nova para a mais antiga, só com PURCHASE, e traz produto e fornecedor sem consultas extras")
    void shouldListNewestFirst() {
        InventoryMovement older = buy(riceUuid, "1", 100L);
        InventoryMovement newer = buy(beansUuid, "2", 200L);
        consume(riceId, "LOSS", "1"); // movimentação de outro tipo, mais recente: não pode aparecer

        List<InventoryMovement> page = service.findAll(PageRequest.of(0, 2)).getContent();

        assertEquals(List.of(newer.getUuid(), older.getUuid()), page.stream().map(InventoryMovement::getUuid).toList());
        assertTrue(page.stream().allMatch(m -> m.getSupplier().getName().equals(tag + " Fornecedor")));
        assertTrue(service.findAll(PageRequest.of(0, 2)).getTotalElements() >= 2);
    }

    @Test
    @DisplayName("Recusa compra de produto ou fornecedor apagado (404) sem mexer no estoque")
    void shouldRejectDeletedReferences() {
        jdbc.update("UPDATE product SET deleted_at = now() WHERE id = ?", beansId);
        jdbc.update("UPDATE supplier SET deleted_at = now() WHERE id = ?", supplierId);

        assertThrows(EntityNotFoundException.class, () -> buy(beansUuid, "1", 100L));
        assertThrows(EntityNotFoundException.class, () -> buy(riceUuid, "1", 100L)); // fornecedor apagado
        assertStock("0", beansId);
        assertStock("0", riceId);
    }
}
