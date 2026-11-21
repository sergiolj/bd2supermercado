package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import br.edu.ucsal.bd2.dto.request.PriceRequest;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.Price;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.service.PriceService;
import br.edu.ucsal.bd2.util.MeasurementType;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;

// Roda o histórico de preços de verdade no PostgreSQL do .env, inclusive o travamento da linha do produto.
// Estes testes NÃO usam @Transactional: precisam do commit real do service e de transações concorrentes. Por isso
// cada teste grava os próprios dados (nomes únicos) e o @AfterEach apaga tudo; o resto do banco não é tocado.
@SpringBootTest
class PriceServiceIntegrationTest {

    @Autowired
    private PriceService service;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    private final String tag = "ZZPRICE" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private Long categoryId;
    private Long productId;
    private UUID productUuid;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> {
            Category category = new Category();
            category.setName(tag + " Categoria");
            em.persist(category);
            categoryId = category.getId();

            Product product = new Product(tag + " Produto", category, "B" + tag, null, MeasurementType.UNIT);
            em.persist(product);
            productId = product.getId();
            productUuid = product.getUuid();
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM price WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    private int currentRows() {
        return jdbc.queryForObject("SELECT count(*) FROM price WHERE product_id = ? AND deleted_at IS NULL", Integer.class, productId);
    }

    private int totalRows() {
        return jdbc.queryForObject("SELECT count(*) FROM price WHERE product_id = ?", Integer.class, productId);
    }

    @Test
    @DisplayName("Mantém o histórico: cada troca fecha o preço antigo exatamente no início do novo, e só um fica vigente")
    void shouldKeepChainedHistory() {
        service.createPrice(productUuid, new PriceRequest(1000L));
        service.updatePrice(productUuid, new PriceRequest(2000L));
        service.updatePrice(productUuid, new PriceRequest(2000L)); // igual ao vigente: não cria linha nova
        service.updatePrice(productUuid, new PriceRequest(3000L));

        assertEquals(3, totalRows());
        assertEquals(1, currentRows());
        assertEquals(3000L, service.findCurrent(productUuid).getValueInCents());

        List<Price> history = service.findHistory(productUuid, PageRequest.of(0, 10)).getContent();
        assertEquals(List.of(3000L, 2000L, 1000L), history.stream().map(Price::getValueInCents).toList());
        assertNull(history.get(0).getDeletedAt()); // o vigente
        assertEquals(history.get(0).getCreatedAt(), history.get(1).getDeletedAt()); // 2000 fechou quando o 3000 começou
        assertEquals(history.get(1).getCreatedAt(), history.get(2).getDeletedAt()); // 1000 fechou quando o 2000 começou
    }

    @Test
    @DisplayName("Pagina o histórico do mais novo para o mais antigo")
    void shouldPaginateHistory() {
        service.createPrice(productUuid, new PriceRequest(100L));
        service.updatePrice(productUuid, new PriceRequest(200L));
        service.updatePrice(productUuid, new PriceRequest(300L));

        var first = service.findHistory(productUuid, PageRequest.of(0, 2));
        var second = service.findHistory(productUuid, PageRequest.of(1, 2));

        assertEquals(3, first.getTotalElements());
        assertEquals(List.of(300L, 200L), first.getContent().stream().map(Price::getValueInCents).toList());
        assertEquals(List.of(100L), second.getContent().stream().map(Price::getValueInCents).toList());
    }

    @Test
    @DisplayName("Tirar de venda fecha o preço vigente, mantém o histórico e permite definir um novo depois")
    void shouldWithdrawFromSaleAndPriceAgain() {
        service.createPrice(productUuid, new PriceRequest(1000L));

        service.deletePrice(productUuid);

        assertEquals(0, currentRows());
        assertEquals(1, totalRows());
        assertThrows(EntityNotFoundException.class, () -> service.findCurrent(productUuid));
        assertThrows(EntityNotFoundException.class, () -> service.updatePrice(productUuid, new PriceRequest(1500L)));

        service.createPrice(productUuid, new PriceRequest(1500L));
        assertEquals(1, currentRows());
        assertEquals(2, totalRows());
    }

    @Test
    @DisplayName("Produto apagado deixa de aceitar preço (404)")
    void shouldRejectDeletedProduct() {
        jdbc.update("UPDATE product SET deleted_at = now() WHERE id = ?", productId);

        assertThrows(EntityNotFoundException.class, () -> service.createPrice(productUuid, new PriceRequest(1000L)));
        assertThrows(EntityNotFoundException.class, () -> service.findCurrent(productUuid));
    }

    // Duas trocas ao mesmo tempo: a outra transação segura a linha do produto, já trocou o preço para 2000 e ainda não
    // confirmou. O service precisa ESPERAR por essa linha e, depois, enxergar o 2000 como o vigente. Sem o travamento
    // ele leria o 1000 (o único confirmado), gravaria o 3000 e deixaria dois preços vigentes.
    @Test
    @DisplayName("Uma troca de preço espera outra em andamento e parte do preço que ela deixou (nunca dois vigentes)")
    void shouldSerializeConcurrentPriceChanges() throws Exception {
        service.createPrice(productUuid, new PriceRequest(1000L));

        runWhileAnotherTransactionHoldsTheProduct(
                () -> jdbc.update("UPDATE price SET deleted_at = now() WHERE product_id = ? AND deleted_at IS NULL", productId),
                () -> jdbc.update("INSERT INTO price (product_id, value_in_cents, created_at) VALUES (?, 2000, now())", productId),
                () -> service.updatePrice(productUuid, new PriceRequest(3000L)));

        assertEquals(1, currentRows());
        assertEquals(3, totalRows());
        assertEquals(3000L, service.findCurrent(productUuid).getValueInCents());
    }

    @Test
    @DisplayName("Dois primeiros preços ao mesmo tempo: o segundo espera e leva 409, sem ficar com dois vigentes")
    void shouldSerializeConcurrentFirstPrice() throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> runWhileAnotherTransactionHoldsTheProduct(
                () -> { },
                () -> jdbc.update("INSERT INTO price (product_id, value_in_cents, created_at) VALUES (?, 1500, now())", productId),
                () -> service.createPrice(productUuid, new PriceRequest(2500L))));

        assertTrue(failure.getCause() instanceof EntityExistsException, String.valueOf(failure.getCause()));
        assertEquals(1, currentRows());
        assertEquals(1, totalRows());
        assertEquals(1500L, service.findCurrent(productUuid).getValueInCents());
    }

    // ---------------------------------------------------------------------------------------------

    // Segura a linha do produto numa transação, faz as alterações dela, inicia o serviço sob teste noutra thread,
    // espera o serviço ficar parado no travamento e só então confirma a primeira. Devolve depois que o serviço termina.
    private void runWhileAnotherTransactionHoldsTheProduct(Runnable closeCurrent, Runnable insertNew, Runnable underTest) throws Exception {
        CountDownLatch holdsLock = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            CompletableFuture<Void> other = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM product WHERE id = ? FOR UPDATE", productId);
                closeCurrent.run();
                insertNew.run();
                holdsLock.countDown();
                await(release);
            }), pool);
            assertTrue(holdsLock.await(10, TimeUnit.SECONDS), "a outra transação não chegou a travar o produto");

            CompletableFuture<Void> mine = CompletableFuture.runAsync(underTest, pool);

            waitUntilBlockedOnProduct();
            release.countDown();
            other.get(10, TimeUnit.SECONDS);
            mine.get(10, TimeUnit.SECONDS);
        } finally {
            // Mesmo se o teste falhar, espera as duas transações terminarem antes do @AfterEach apagar os dados:
            // senão uma thread atrasada poderia gravar depois da limpeza e deixar lixo no banco.
            release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(20, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        }
    }

    // O SELECT ... FOR UPDATE do service aparece como a consulta ativa da sessão; "Lock" é a espera pela linha do produto.
    private void waitUntilBlockedOnProduct() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query ILIKE 'select%from product%'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("o service não esperou pelo travamento do produto (não está usando SELECT ... FOR UPDATE?)");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("tempo esgotado esperando o sinal do teste");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
