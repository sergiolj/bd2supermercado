package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dto.request.PaymentRequest;
import br.edu.ucsal.bd2.dto.request.SaleItemRequest;
import br.edu.ucsal.bd2.dto.request.SaleRequest;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.Price;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.service.SaleService;
import br.edu.ucsal.bd2.util.MeasurementType;
import br.edu.ucsal.bd2.util.PaymentMethod;
import br.edu.ucsal.bd2.util.PaymentStatus;
import br.edu.ucsal.bd2.util.SaleStatus;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;

// Roda o fluxo da venda de verdade no PostgreSQL do .env, com o trigger que baixa o estoque de cada loja.
// Estes testes NÃO usam @Transactional: o que se testa é justamente a transação do service (o pagamento que não
// consegue baixar o estoque some junto) e a trava entre duas transações ao mesmo tempo. Por isso cada teste grava os
// próprios dados (nomes únicos) e o @AfterEach apaga tudo; o resto do banco (inclusive o seed) não é tocado.
@SpringBootTest
class SaleServiceIntegrationTest {

    @Autowired
    private SaleService service;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    private final String tag = "ZZSALE" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private Long categoryId;
    private Long storeId;
    private Long otherStoreId;
    private UUID storeUuid;
    private UUID otherStoreUuid;
    private Long sellerId;
    private Long otherSellerId;
    private UUID sellerUuid;
    private UUID otherSellerUuid;
    private Long riceId;
    private Long beansId;
    private UUID riceUuid;
    private UUID beansUuid;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> {
            Category category = new Category();
            category.setName(tag + " Categoria");
            em.persist(category);
            categoryId = category.getId();

            Store store = store("Loja");
            Store other = store("Outra Loja");
            storeId = store.getId();
            storeUuid = store.getUuid();
            otherStoreId = other.getId();
            otherStoreUuid = other.getUuid();

            Staff seller = cashier(store);
            Staff otherSeller = cashier(other);
            sellerId = seller.getId();
            sellerUuid = seller.getUuid();
            otherSellerId = otherSeller.getId();
            otherSellerUuid = otherSeller.getUuid();

            Product rice = product(category, "Arroz", MeasurementType.UNIT, 2899L);
            Product beans = product(category, "Feijao", MeasurementType.KG, 3999L);
            riceId = rice.getId();
            riceUuid = rice.getUuid();
            beansId = beans.getId();
            beansUuid = beans.getUuid();
        });
    }

    @AfterEach
    void cleanUp() {
        // As saídas (SALE) saem antes das entradas: o trigger desfaz cada movimentação removida, e remover a compra
        // primeiro deixaria o estoque negativo (o CHECK recusaria).
        jdbc.update("DELETE FROM payment WHERE sale_id IN (SELECT id FROM sale WHERE store_id IN (?, ?))", storeId, otherStoreId);
        jdbc.update("DELETE FROM inventory_movement WHERE type <> 'PURCHASE' AND product_id IN (?, ?)", riceId, beansId);
        jdbc.update("DELETE FROM inventory_movement WHERE product_id IN (?, ?)", riceId, beansId);
        jdbc.update("DELETE FROM sale_items WHERE product_id IN (?, ?)", riceId, beansId);
        jdbc.update("DELETE FROM sale WHERE store_id IN (?, ?)", storeId, otherStoreId);
        jdbc.update("DELETE FROM inventory WHERE product_id IN (?, ?)", riceId, beansId);
        jdbc.update("DELETE FROM price WHERE product_id IN (?, ?)", riceId, beansId);
        jdbc.update("DELETE FROM product WHERE id IN (?, ?)", riceId, beansId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM staff WHERE id IN (?, ?)", sellerId, otherSellerId);
        jdbc.update("DELETE FROM person WHERE id IN (?, ?)", sellerId, otherSellerId);
        jdbc.update("DELETE FROM store WHERE id IN (?, ?)", storeId, otherStoreId);
    }

    private Store store(String name) {
        Store store = new Store();
        store.setName(tag + " " + name);
        em.persist(store);
        return store;
    }

    private Staff cashier(Store store) {
        Staff staff = new Staff();
        staff.setName(tag + " Caixa");
        staff.setTaxNumber(String.valueOf(ThreadLocalRandom.current().nextLong(10_000_000_000L, 100_000_000_000L)));
        staff.setRole(StaffRole.CASHIER);
        staff.setStore(store);
        em.persist(staff);
        return staff;
    }

    private Product product(Category category, String name, MeasurementType unit, long priceInCents) {
        Product product = new Product(tag + " " + name, category, name.charAt(0) + tag, null, unit);
        em.persist(product);
        Price price = new Price();
        price.setProduct(product);
        price.setValueInCents(priceInCents);
        em.persist(price);
        return product;
    }

    // Entrada de estoque gravada direto, como o seed faz: o trigger soma ao saldo da loja.
    private void stockUp(Long store, Long productId, String quantity) {
        jdbc.update("INSERT INTO inventory_movement (store_id, product_id, type, quantity, created_at) VALUES (?, ?, 'PURCHASE', ?, now())",
                store, productId, new BigDecimal(quantity));
    }

    private SaleItemRequest item(UUID productUuid, String quantity) {
        return new SaleItemRequest(productUuid, new BigDecimal(quantity));
    }

    private Sale open(SaleItemRequest... items) {
        return service.openSale(storeUuid, new SaleRequest(sellerUuid, null, List.of(items)));
    }

    private Sale pay(Sale sale, long amountInCents) {
        return service.registerPayment(storeUuid, sale.getUuid(), new PaymentRequest(PaymentMethod.PIX, amountInCents));
    }

    private BigDecimal stock(Long store, Long productId) {
        List<BigDecimal> rows = jdbc.queryForList("SELECT total_quantity FROM inventory WHERE store_id = ? AND product_id = ?",
                BigDecimal.class, store, productId);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    private void assertStock(String expected, Long productId) {
        assertEquals(0, new BigDecimal(expected).compareTo(stock(storeId, productId)), "estoque: " + stock(storeId, productId));
    }

    private String status(Sale sale) {
        return jdbc.queryForObject("SELECT status FROM sale WHERE uuid = ?", String.class, sale.getUuid());
    }

    private int movements(Sale sale) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_movement m JOIN sale s ON s.id = m.sale_id "
                + "WHERE s.uuid = ? AND m.type = 'SALE'", Integer.class, sale.getUuid());
    }

    private List<String> payments(Sale sale) {
        return jdbc.queryForList("SELECT p.status || ':' || p.amount_in_cents FROM payment p JOIN sale s ON s.id = p.sale_id "
                + "WHERE s.uuid = ? ORDER BY p.id", String.class, sale.getUuid());
    }

    private static void assertConflict(Runnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode(), error.getReason());
    }

    // Dispara as duas operações juntas, cada uma na sua transação, e devolve o que cada uma deu ("ok" ou o status HTTP).
    private List<String> race(Supplier<Sale> first, Supplier<Sale> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<String>> runs = new ArrayList<>();
        for (Supplier<Sale> action : List.of(first, second)) {
            runs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    action.get();
                    return "ok";
                } catch (ResponseStatusException e) {
                    return String.valueOf(e.getStatusCode().value());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrompido";
                }
            }));
        }
        start.countDown();

        List<String> outcomes = new ArrayList<>();
        for (CompletableFuture<String> run : runs) {
            outcomes.add(run.get(30, TimeUnit.SECONDS));
        }
        return outcomes.stream().sorted().toList();
    }

    @Test
    @DisplayName("A venda só baixa o estoque quando é paga integralmente: uma movimentação SALE por item, junto do último pagamento")
    void shouldCompleteSaleWhenFullyPaid() {
        stockUp(storeId, riceId, "10");
        stockUp(storeId, beansId, "5");

        // 3 x 2899 = 8697; 1,25 kg x 3999 = 4998,75 -> 4999. Total 13696.
        Sale sale = open(item(riceUuid, "3"), item(beansUuid, "1.250"));
        assertEquals(SaleStatus.OPEN, sale.getStatus());
        assertEquals(13696L, sale.getTotalAmountInCents());
        assertStock("10", riceId);
        assertStock("5", beansId);
        assertEquals(0, movements(sale));

        // Pagamento parcial: a venda continua em aberto e nada sai do estoque.
        Sale partial = pay(sale, 5000);
        assertEquals(SaleStatus.OPEN, partial.getStatus());
        assertEquals(8696L, partial.remainingInCents());
        assertStock("10", riceId);
        assertEquals(0, movements(sale));

        // O pagamento que quita o total efetiva a venda.
        Sale completed = service.registerPayment(storeUuid, sale.getUuid(), new PaymentRequest(PaymentMethod.CREDIT_CARD, 8696L));
        assertEquals(SaleStatus.COMPLETED, completed.getStatus());
        assertEquals("COMPLETED", status(sale));
        assertEquals(2, movements(sale));
        assertStock("7", riceId);
        assertStock("3.75", beansId);
        assertEquals(List.of("PAID:5000", "PAID:8696"), payments(sale));

        // As movimentações são da loja da venda e levam a quantidade de cada item.
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inventory_movement m JOIN sale s ON s.id = m.sale_id "
                + "WHERE s.uuid = ? AND m.store_id = ? AND m.product_id = ? AND m.quantity = 1.250", Integer.class,
                sale.getUuid(), storeId, beansId));

        Sale found = service.findByUuid(storeUuid, sale.getUuid());
        assertEquals(List.of(tag + " Arroz", tag + " Feijao"), found.getItems().stream().map(i -> i.getProduct().getName()).toList());
        assertEquals(13696L, found.paidInCents());
        assertEquals(tag + " Caixa", found.getSeller().getName());
    }

    @Test
    @DisplayName("Se o estoque acabou antes do pagamento final, nada é gravado: nem o pagamento, nem as baixas, e a venda segue aberta")
    void shouldRollbackPaymentWhenStockRanOut() {
        stockUp(storeId, riceId, "5");
        Sale sale = open(item(riceUuid, "4"));
        pay(sale, 1000);

        // Outra venda leva 3 dos 5 antes deste cliente terminar de pagar: sobram 2.
        Sale other = open(item(riceUuid, "3"));
        pay(other, other.getTotalAmountInCents());
        assertStock("2", riceId);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> pay(sale, sale.getTotalAmountInCents() - 1000));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains(tag + " Arroz") && error.getReason().contains("disponível 2"), error.getReason());

        assertEquals("OPEN", status(sale));
        assertEquals(List.of("PAID:1000"), payments(sale)); // só o pagamento anterior ficou
        assertEquals(0, movements(sale));
        assertStock("2", riceId);
    }

    @Test
    @DisplayName("Recusa abrir a venda com item sem saldo na loja (409) e não grava nada")
    void shouldRefuseOpeningWithoutStock() {
        stockUp(storeId, riceId, "2");
        stockUp(otherStoreId, beansId, "50"); // saldo em outra loja não conta

        assertConflict(() -> open(item(riceUuid, "3")));
        assertConflict(() -> open(item(riceUuid, "1"), item(beansUuid, "1")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sale WHERE store_id = ?", Integer.class, storeId));
        assertStock("2", riceId);
    }

    @Test
    @DisplayName("Cancelar a venda em aberto estorna os pagamentos e não mexe no estoque; venda cancelada ou concluída não muda mais")
    void shouldCancelOpenSale() {
        stockUp(storeId, riceId, "10");
        Sale sale = open(item(riceUuid, "2"));
        pay(sale, 1000);

        Sale canceled = service.cancelSale(storeUuid, sale.getUuid());
        assertEquals(SaleStatus.CANCELED, canceled.getStatus());
        assertEquals(List.of("REFUNDED:1000"), payments(sale));
        assertEquals(0, movements(sale));
        assertStock("10", riceId);
        assertConflict(() -> pay(sale, 100));
        assertConflict(() -> service.cancelSale(storeUuid, sale.getUuid()));

        Sale completed = open(item(riceUuid, "1"));
        pay(completed, completed.getTotalAmountInCents());
        assertConflict(() -> service.cancelSale(storeUuid, completed.getUuid()));
        assertConflict(() -> pay(completed, 1));
        assertStock("9", riceId);
    }

    @Test
    @DisplayName("O vendedor precisa ser da loja da venda, e uma loja não acha, não recebe nem cancela a venda de outra")
    void shouldKeepSalesPerStore() {
        stockUp(storeId, riceId, "10");

        assertConflict(() -> service.openSale(storeUuid, new SaleRequest(otherSellerUuid, null, List.of(item(riceUuid, "1")))));

        Sale sale = open(item(riceUuid, "1"));
        PaymentRequest payment = new PaymentRequest(PaymentMethod.CASH, sale.getTotalAmountInCents());
        assertThrows(EntityNotFoundException.class, () -> service.findByUuid(otherStoreUuid, sale.getUuid()));
        assertThrows(EntityNotFoundException.class, () -> service.registerPayment(otherStoreUuid, sale.getUuid(), payment));
        assertThrows(EntityNotFoundException.class, () -> service.cancelSale(otherStoreUuid, sale.getUuid()));
        assertEquals("OPEN", status(sale));
        assertEquals(List.of(), payments(sale));
    }

    @Test
    @DisplayName("Duas vendas quitadas ao mesmo tempo disputando o mesmo saldo: uma conclui, a outra recebe 409, e o estoque nunca fica negativo")
    void shouldSerializeCompletionsCompetingForStock() throws Exception {
        stockUp(storeId, riceId, "5");
        Sale first = open(item(riceUuid, "3"));
        Sale second = open(item(riceUuid, "3"));

        List<String> outcomes = race(() -> pay(first, first.getTotalAmountInCents()), () -> pay(second, second.getTotalAmountInCents()));

        assertEquals(List.of("409", "ok"), outcomes);
        assertStock("2", riceId);
        assertEquals(1, movements(first) + movements(second));
    }

    @Test
    @DisplayName("Dois pagamentos que quitam a mesma venda ao mesmo tempo: só um entra, e a venda é efetivada uma vez só")
    void shouldSerializePaymentsOfTheSameSale() throws Exception {
        stockUp(storeId, riceId, "10");
        Sale sale = open(item(riceUuid, "2"));

        List<String> outcomes = race(() -> pay(sale, sale.getTotalAmountInCents()), () -> pay(sale, sale.getTotalAmountInCents()));

        assertEquals(List.of("409", "ok"), outcomes);
        assertEquals(List.of("PAID:" + sale.getTotalAmountInCents()), payments(sale));
        assertEquals(1, movements(sale));
        assertStock("8", riceId);
    }
}
