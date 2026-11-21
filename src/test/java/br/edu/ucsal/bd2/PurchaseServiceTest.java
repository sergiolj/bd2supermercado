package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.InventoryMovementDAO;
import br.edu.ucsal.bd2.dto.request.PurchaseRequest;
import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.model.Supplier;
import br.edu.ucsal.bd2.repository.ProductRepository;
import br.edu.ucsal.bd2.repository.SupplierRepository;
import br.edu.ucsal.bd2.service.CriteriaQueryService;
import br.edu.ucsal.bd2.service.PurchaseService;
import br.edu.ucsal.bd2.service.StoreService;
import br.edu.ucsal.bd2.util.MeasurementType;
import br.edu.ucsal.bd2.util.MovementType;
import jakarta.persistence.EntityNotFoundException;

// Teste de unidade puro: sem Spring e sem banco, só o service com o DAO e os repositórios simulados.
// O efeito no estoque (trigger) é testado contra o PostgreSQL em PurchaseServiceIntegrationTest.
class PurchaseServiceTest {

    private final InventoryMovementDAO dao = mock(InventoryMovementDAO.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final SupplierRepository supplierRepository = mock(SupplierRepository.class);
    private final StoreService storeService = mock(StoreService.class);
    private final PurchaseService service = new PurchaseService(dao, productRepository, supplierRepository, storeService);

    private final Store store = store("Loja Pituba");

    private final Product rice = product("Arroz 5kg", MeasurementType.UNIT);
    private final Product beans = product("Feijão a granel", MeasurementType.KG);
    private final Supplier supplier = supplier();

    private Product product(String name, MeasurementType unit) {
        Product product = new Product();
        product.setUuid(UUID.randomUUID());
        product.setName(name);
        product.setUnitOfMeasure(unit);
        when(productRepository.findByUuidAndDeletedAtIsNull(product.getUuid())).thenReturn(Optional.of(product));
        return product;
    }

    // Loja do header X-Store-Id: o StoreService simulado a acha pelo uuid.
    private Store store(String name) {
        Store store = new Store();
        store.setUuid(UUID.randomUUID());
        store.setName(name);
        when(storeService.findByUuid(store.getUuid())).thenReturn(store);
        return store;
    }

    private Supplier supplier() {
        Supplier supplier = new Supplier();
        supplier.setUuid(UUID.randomUUID());
        supplier.setName("Distribuidora Bahia");
        when(supplierRepository.findByUuidAndDeletedAtIsNull(supplier.getUuid())).thenReturn(Optional.of(supplier));
        return supplier;
    }

    private PurchaseRequest request(Product product, String quantity, Long unitCost, LocalDate batchExpiresAt) {
        return new PurchaseRequest(product.getUuid(), supplier.getUuid(), quantity == null ? null : new BigDecimal(quantity),
                unitCost, batchExpiresAt);
    }

    private void assertBadRequest(PurchaseRequest request) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.registerPurchase(store.getUuid(), request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar a compra como movimentação PURCHASE da loja do header, com produto, fornecedor, quantidade e custo")
    void shouldRegisterPurchase() {
        LocalDate expiresAt = LocalDate.now(CriteriaQueryService.STORE_ZONE).plusMonths(6);

        InventoryMovement result = service.registerPurchase(store.getUuid(), request(rice, "24", 1850L, expiresAt));

        assertEquals(MovementType.PURCHASE, result.getType());
        assertSame(store, result.getStore());
        assertSame(rice, result.getProduct());
        assertSame(supplier, result.getSupplier());
        assertEquals(0, new BigDecimal("24").compareTo(result.getQuantity()));
        assertEquals(1850L, result.getUnitCostInCents());
        assertEquals(expiresAt, result.getBatchExpiresAt());
        assertNull(result.getSale());
        verify(dao).save(result);
    }

    @Test
    @DisplayName("Deve aceitar quantidade fracionada em produto por peso e validade hoje ou sem validade")
    void shouldAcceptFractionAndOptionalExpiration() {
        assertEquals(0, new BigDecimal("12.5").compareTo(
                service.registerPurchase(store.getUuid(), request(beans, "12.500", 899L, null)).getQuantity()));
        service.registerPurchase(store.getUuid(), request(beans, "1", 899L, LocalDate.now(CriteriaQueryService.STORE_ZONE)));
    }

    @Test
    @DisplayName("Deve retornar 400 para quantidade zero, negativa, ausente, com mais de 3 casas ou grande demais")
    void shouldRejectInvalidQuantity() {
        assertBadRequest(request(beans, "0", 100L, null));
        assertBadRequest(request(beans, "-1", 100L, null));
        assertBadRequest(request(beans, null, 100L, null));
        assertBadRequest(request(beans, "0.1234", 100L, null));
        assertBadRequest(request(beans, "1000000000", 100L, null)); // não cabe em numeric(12,3)
    }

    @Test
    @DisplayName("Deve exigir quantidade inteira para produto por unidade")
    void shouldRequireWholeQuantityForUnitProducts() {
        assertBadRequest(request(rice, "1.5", 100L, null));
        assertEquals(0, new BigDecimal("2").compareTo(service.registerPurchase(store.getUuid(), request(rice, "2.000", 100L, null)).getQuantity()));
    }

    @Test
    @DisplayName("Deve retornar 400 para custo ausente, zero, negativo ou acima do limite")
    void shouldRejectInvalidUnitCost() {
        assertBadRequest(request(rice, "1", null, null));
        assertBadRequest(request(rice, "1", 0L, null));
        assertBadRequest(request(rice, "1", -5L, null));
        assertBadRequest(request(rice, "1", 1_000_000_001L, null));
    }

    @Test
    @DisplayName("Deve retornar 400 para validade de lote que já passou")
    void shouldRejectPastExpiration() {
        assertBadRequest(request(rice, "1", 100L, LocalDate.now(CriteriaQueryService.STORE_ZONE).minusDays(1)));
    }

    @Test
    @DisplayName("Deve retornar 400 sem produto ou sem fornecedor, e 404 quando eles não existem")
    void shouldValidateReferences() {
        assertBadRequest(new PurchaseRequest(null, supplier.getUuid(), BigDecimal.ONE, 100L, null));
        assertBadRequest(new PurchaseRequest(rice.getUuid(), null, BigDecimal.ONE, 100L, null));

        UUID unknown = UUID.randomUUID();
        when(productRepository.findByUuidAndDeletedAtIsNull(unknown)).thenReturn(Optional.empty());
        when(supplierRepository.findByUuidAndDeletedAtIsNull(unknown)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.registerPurchase(store.getUuid(), new PurchaseRequest(unknown, supplier.getUuid(), BigDecimal.ONE, 100L, null)));
        assertThrows(EntityNotFoundException.class,
                () -> service.registerPurchase(store.getUuid(), new PurchaseRequest(rice.getUuid(), unknown, BigDecimal.ONE, 100L, null)));
        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 404 quando a loja do header não existe, sem lançar nada")
    void shouldRejectUnknownStore() {
        UUID unknown = UUID.randomUUID();
        when(storeService.findByUuid(unknown)).thenThrow(new EntityNotFoundException("Loja com uuid: " + unknown + " não encontrada."));

        assertThrows(EntityNotFoundException.class, () -> service.registerPurchase(unknown, request(rice, "1", 100L, null)));
        assertThrows(EntityNotFoundException.class, () -> service.findAll(unknown, null));
        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 404 quando a compra não existe na loja (ou o uuid é de outro tipo de movimentação)")
    void shouldReturnNotFound() {
        UUID unknown = UUID.randomUUID();
        when(dao.findPurchaseByUuid(store, unknown)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.findByUuid(store.getUuid(), unknown));
        assertThrows(EntityNotFoundException.class, () -> service.deletePurchase(store.getUuid(), unknown));
    }

    @Test
    @DisplayName("Uma loja não acha nem apaga a compra de outra loja")
    void shouldNotSeePurchaseOfAnotherStore() {
        InventoryMovement purchase = existingPurchase();
        Store other = store("Loja Barra");
        when(dao.findPurchaseByUuid(other, purchase.getUuid())).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.findByUuid(other.getUuid(), purchase.getUuid()));
        assertThrows(EntityNotFoundException.class, () -> service.deletePurchase(other.getUuid(), purchase.getUuid()));
        verify(dao, never()).removeAndFlush(any());
    }

    @Test
    @DisplayName("Deve apagar a compra (o estorno no estoque é feito pelo trigger)")
    void shouldDeletePurchase() {
        InventoryMovement purchase = existingPurchase();

        service.deletePurchase(store.getUuid(), purchase.getUuid());

        verify(dao).removeAndFlush(purchase);
    }

    @Test
    @DisplayName("Deve converter a recusa do trigger (estoque ficaria negativo) em 409")
    void shouldTranslateStockTriggerRefusal() {
        InventoryMovement purchase = existingPurchase();
        doThrow(new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("ERROR: Estoque insuficiente: a operacao (DELETE de 24 unidades) deixaria o estoque do produto 7 na loja 1 negativo")))
                .when(dao).removeAndFlush(purchase);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.deletePurchase(store.getUuid(), purchase.getUuid()));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("Arroz 5kg"));
    }

    @Test
    @DisplayName("Não deve esconder outras violações de integridade do banco")
    void shouldRethrowOtherIntegrityViolations() {
        InventoryMovement purchase = existingPurchase();
        DataIntegrityViolationException other = new DataIntegrityViolationException("fk",
                new RuntimeException("ERROR: update or delete violates foreign key constraint"));
        doThrow(other).when(dao).removeAndFlush(purchase);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> service.deletePurchase(store.getUuid(), purchase.getUuid()));

        assertSame(other, thrown);
    }

    private InventoryMovement existingPurchase() {
        InventoryMovement purchase = new InventoryMovement();
        purchase.setUuid(UUID.randomUUID());
        purchase.setType(MovementType.PURCHASE);
        purchase.setStore(store);
        purchase.setProduct(rice);
        purchase.setSupplier(supplier);
        purchase.setQuantity(new BigDecimal("24"));
        when(dao.findPurchaseByUuid(store, purchase.getUuid())).thenReturn(Optional.of(purchase));
        return purchase;
    }
}
