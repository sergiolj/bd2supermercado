package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.InventoryMovementDAO;
import br.edu.ucsal.bd2.dao.PriceDAO;
import br.edu.ucsal.bd2.dao.SaleDAO;
import br.edu.ucsal.bd2.dto.request.PaymentRequest;
import br.edu.ucsal.bd2.dto.request.SaleItemRequest;
import br.edu.ucsal.bd2.dto.request.SaleRequest;
import br.edu.ucsal.bd2.model.Inventory;
import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.model.Payment;
import br.edu.ucsal.bd2.model.Price;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.model.SaleItem;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.repository.InventoryRepository;
import br.edu.ucsal.bd2.repository.ProductRepository;
import br.edu.ucsal.bd2.service.SaleService;
import br.edu.ucsal.bd2.service.StaffService;
import br.edu.ucsal.bd2.service.StoreService;
import br.edu.ucsal.bd2.util.MeasurementType;
import br.edu.ucsal.bd2.util.MovementType;
import br.edu.ucsal.bd2.util.PaymentMethod;
import br.edu.ucsal.bd2.util.PaymentStatus;
import br.edu.ucsal.bd2.util.SaleStatus;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityNotFoundException;

// Teste de unidade puro: sem Spring e sem banco, só o service com DAOs e repositórios simulados.
// A transação, o trigger que baixa o estoque e as travas são testados contra o PostgreSQL em SaleServiceIntegrationTest.
class SaleServiceTest {

    private final SaleDAO dao = mock(SaleDAO.class);
    private final InventoryMovementDAO movementDao = mock(InventoryMovementDAO.class);
    private final PriceDAO priceDao = mock(PriceDAO.class);
    private final InventoryRepository inventoryRepository = mock(InventoryRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final StaffService staffService = mock(StaffService.class);
    private final StoreService storeService = mock(StoreService.class);
    private final SaleService service = new SaleService(dao, movementDao, priceDao, inventoryRepository, productRepository,
            staffService, storeService);

    private final AtomicLong ids = new AtomicLong();

    // Saldo da loja que os repositórios simulados devolvem, com e sem trava.
    private final List<Inventory> stock = new ArrayList<>();
    private final List<Inventory> lockedStock = new ArrayList<>();

    private final Store store = store("Loja Pituba");
    private final Staff seller = staff(store);

    private final Product rice = product("Arroz 5kg", MeasurementType.UNIT, 2899L);
    private final Product beans = product("Feijão a granel", MeasurementType.KG, 3999L);

    {
        when(inventoryRepository.findByStoreAndProductIn(eq(store), any())).thenAnswer(call -> stock);
        when(inventoryRepository.lockByStoreAndProducts(eq(store), any())).thenAnswer(call -> lockedStock);
    }

    private Store store(String name) {
        Store store = new Store();
        store.setUuid(UUID.randomUUID());
        store.setName(name);
        when(storeService.findByUuid(store.getUuid())).thenReturn(store);
        return store;
    }

    private Staff staff(Store store) {
        Staff staff = new Staff();
        staff.setUuid(UUID.randomUUID());
        staff.setName("Caixa " + (store == null ? "da rede" : store.getName()));
        staff.setRole(store == null ? StaffRole.ADMIN : StaffRole.CASHIER);
        staff.setStore(store);
        when(staffService.findByUuid(staff.getUuid())).thenReturn(staff);
        return staff;
    }

    private Product product(String name, MeasurementType unit, Long priceInCents) {
        Product product = new Product();
        product.setId(ids.incrementAndGet());
        product.setUuid(UUID.randomUUID());
        product.setName(name);
        product.setUnitOfMeasure(unit);
        when(productRepository.findByUuidAndDeletedAtIsNull(product.getUuid())).thenReturn(Optional.of(product));

        Price price = null;
        if (priceInCents != null) {
            price = new Price();
            price.setProduct(product);
            price.setValueInCents(priceInCents);
        }
        when(priceDao.findCurrent(product)).thenReturn(Optional.ofNullable(price));
        return product;
    }

    private static Inventory row(Product product, String quantity) {
        Inventory row = new Inventory();
        row.setProduct(product);
        row.setTotalQuantity(new BigDecimal(quantity));
        return row;
    }

    private static SaleItemRequest item(Product product, String quantity) {
        return new SaleItemRequest(product.getUuid(), quantity == null ? null : new BigDecimal(quantity));
    }

    private SaleRequest request(SaleItemRequest... items) {
        return new SaleRequest(seller.getUuid(), null, Arrays.asList(items));
    }

    // Venda já gravada, como o DAO a devolve: com itens e pagamentos carregados.
    private Sale existingSale(SaleStatus status, PaymentStatus... payments) {
        Sale sale = new Sale();
        sale.setUuid(UUID.randomUUID());
        sale.setStore(store);
        sale.setSeller(seller);
        sale.setStatus(status);
        for (Object[] line : new Object[][] { { rice, "2" }, { beans, "1.000" } }) {
            SaleItem item = new SaleItem();
            item.setSale(sale);
            item.setProduct((Product) line[0]);
            item.setQuantity(new BigDecimal((String) line[1]));
            item.setUnitPriceInCents(((Product) line[0]) == rice ? 2899L : 3999L);
            sale.getItems().add(item);
        }
        sale.setTotalAmountInCents(9797L); // 2 x 2899 + 1 x 3999
        for (PaymentStatus paymentStatus : payments) {
            Payment payment = new Payment();
            payment.setSale(sale);
            payment.setMethod(PaymentMethod.PIX);
            payment.setAmountInCents(1000L);
            payment.setStatus(paymentStatus);
            sale.getPayments().add(payment);
        }
        when(dao.findInStore(eq(store), eq(sale.getUuid()), anyBoolean())).thenReturn(Optional.of(sale));
        return sale;
    }

    private Sale pay(Sale sale, Long amountInCents) {
        return service.registerPayment(store.getUuid(), sale.getUuid(), new PaymentRequest(PaymentMethod.PIX, amountInCents));
    }

    private static void assertStatus(HttpStatus expected, Executable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action);
        assertEquals(expected, error.getStatusCode(), error.getReason());
    }

    private void assertNothingWritten() {
        verify(dao, never()).save(any());
        verify(dao, never()).savePayment(any());
        verify(movementDao, never()).save(any());
    }

    @Test
    @DisplayName("Deve abrir a venda em aberto, com o preço vigente de cada produto e o total arredondado por item, sem baixar estoque")
    void shouldOpenSaleWithCurrentPrices() {
        stock.addAll(List.of(row(rice, "10"), row(beans, "5")));

        Sale sale = service.openSale(store.getUuid(),
                new SaleRequest(seller.getUuid(), "123.456.789-09", List.of(item(rice, "3"), item(beans, "1.25"))));

        assertEquals(SaleStatus.OPEN, sale.getStatus());
        assertSame(store, sale.getStore());
        assertSame(seller, sale.getSeller());
        assertEquals("12345678909", sale.getClientTaxNumber());
        assertEquals(List.of(2899L, 3999L), sale.getItems().stream().map(SaleItem::getUnitPriceInCents).toList());
        assertTrue(sale.getItems().stream().allMatch(item -> item.getSale() == sale));
        assertEquals(0, new BigDecimal("1.250").compareTo(sale.getItems().get(1).getQuantity()));
        assertEquals(13696L, sale.getTotalAmountInCents()); // 3 x 2899 = 8697; 1,25 x 3999 = 4998,75 -> 4999
        assertEquals(13696L, sale.remainingInCents());
        verify(dao).save(sale);
        verify(movementDao, never()).save(any());
        verify(inventoryRepository, never()).lockByStoreAndProducts(any(), any());
    }

    @Test
    @DisplayName("Deve retornar 400 para venda sem itens, item sem produto, produto repetido, quantidade inválida ou item abaixo de 1 centavo")
    void shouldRejectInvalidItems() {
        stock.addAll(List.of(row(rice, "10"), row(beans, "5")));
        Product cheap = product("Sal a granel", MeasurementType.KG, 1L);

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.openSale(store.getUuid(), new SaleRequest(seller.getUuid(), null, List.of())));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.openSale(store.getUuid(), new SaleRequest(seller.getUuid(), null, null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.openSale(store.getUuid(), request(new SaleItemRequest(null, BigDecimal.ONE))));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.openSale(store.getUuid(), request(item(rice, "1"), item(rice, "2"))));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.openSale(store.getUuid(), request(item(rice, "1.5"))));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.openSale(store.getUuid(), request(item(beans, "0"))));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.openSale(store.getUuid(), request(item(beans, null))));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.openSale(store.getUuid(), request(item(cheap, "0.001")))); // 0,001 centavo
        assertNothingWritten();
    }

    @Test
    @DisplayName("Deve retornar 409 para produto sem preço vigente e 404 para produto inexistente")
    void shouldRejectProductNotForSale() {
        Product unpriced = product("Produto fora de linha", MeasurementType.UNIT, null);
        UUID unknown = UUID.randomUUID();
        when(productRepository.findByUuidAndDeletedAtIsNull(unknown)).thenReturn(Optional.empty());

        assertStatus(HttpStatus.CONFLICT, () -> service.openSale(store.getUuid(), request(item(unpriced, "1"))));
        assertThrows(EntityNotFoundException.class,
                () -> service.openSale(store.getUuid(), request(new SaleItemRequest(unknown, BigDecimal.ONE))));
        assertNothingWritten();
    }

    @Test
    @DisplayName("Deve exigir um vendedor que trabalhe na loja da venda (400 sem vendedor, 409 de outra loja ou ADMIN)")
    void shouldRequireSellerOfTheStore() {
        stock.add(row(rice, "10"));
        Staff fromAnotherStore = staff(store("Loja Barra"));
        Staff admin = staff(null);

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.openSale(store.getUuid(), new SaleRequest(null, null, List.of(item(rice, "1")))));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.openSale(store.getUuid(), new SaleRequest(fromAnotherStore.getUuid(), null, List.of(item(rice, "1")))));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.openSale(store.getUuid(), new SaleRequest(admin.getUuid(), null, List.of(item(rice, "1")))));
        assertNothingWritten();
    }

    @Test
    @DisplayName("Deve retornar 400 para CPF/CNPJ na nota que não tem 11 nem 14 dígitos")
    void shouldRejectInvalidClientTaxNumber() {
        stock.add(row(rice, "10"));

        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.openSale(store.getUuid(), new SaleRequest(seller.getUuid(), "123", List.of(item(rice, "1")))));
        assertEquals("12345678000195", service.openSale(store.getUuid(),
                new SaleRequest(seller.getUuid(), "12.345.678/0001-95", List.of(item(rice, "1")))).getClientTaxNumber());
        assertNull(service.openSale(store.getUuid(), new SaleRequest(seller.getUuid(), "  ", List.of(item(rice, "1")))).getClientTaxNumber());
    }

    @Test
    @DisplayName("Deve recusar abrir a venda com 409 quando falta saldo, listando todos os itens em falta")
    void shouldRefuseOpeningWithoutStock() {
        stock.add(row(rice, "2")); // feijão nunca entrou na loja: saldo zero

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.openSale(store.getUuid(), request(item(rice, "3"), item(beans, "1"))));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("'Arroz 5kg' (pedido 3, disponível 2)"), error.getReason());
        assertTrue(error.getReason().contains("'Feijão a granel' (pedido 1, disponível 0)"), error.getReason());
        assertNothingWritten();
    }

    @Test
    @DisplayName("Pagamento parcial: registra o pagamento como PAID e a venda continua em aberto, sem baixar estoque")
    void shouldKeepSaleOpenOnPartialPayment() {
        Sale sale = existingSale(SaleStatus.OPEN);

        Sale result = pay(sale, 4000L);

        ArgumentCaptor<Payment> payment = ArgumentCaptor.forClass(Payment.class);
        verify(dao).savePayment(payment.capture());
        assertEquals(PaymentStatus.PAID, payment.getValue().getStatus());
        assertEquals(4000L, payment.getValue().getAmountInCents());
        assertSame(sale, payment.getValue().getSale());
        assertNotNull(payment.getValue().getPaidAt());
        assertEquals(SaleStatus.OPEN, result.getStatus());
        assertEquals(5797L, result.remainingInCents());
        verify(movementDao, never()).save(any());
        verify(inventoryRepository, never()).lockByStoreAndProducts(any(), any());
    }

    @Test
    @DisplayName("Pagamento que quita o total: trava o estoque, lança uma movimentação SALE por item e conclui a venda")
    void shouldCompleteSaleOnFullPayment() {
        Sale sale = existingSale(SaleStatus.OPEN, PaymentStatus.PAID, PaymentStatus.FAILED); // o recusado não conta
        lockedStock.addAll(List.of(row(rice, "2"), row(beans, "1"))); // exatamente o que a venda leva

        Sale result = pay(sale, 8797L);

        assertEquals(SaleStatus.COMPLETED, result.getStatus());
        assertEquals(0L, result.remainingInCents());
        verify(inventoryRepository).lockByStoreAndProducts(eq(store), any());
        verify(dao).savePayment(any());

        ArgumentCaptor<InventoryMovement> movements = ArgumentCaptor.forClass(InventoryMovement.class);
        verify(movementDao, times(2)).save(movements.capture());
        assertEquals(List.of(rice, beans), movements.getAllValues().stream().map(InventoryMovement::getProduct).toList());
        for (int i = 0; i < 2; i++) {
            InventoryMovement movement = movements.getAllValues().get(i);
            assertEquals(MovementType.SALE, movement.getType());
            assertSame(store, movement.getStore());
            assertSame(sale, movement.getSale());
            assertEquals(sale.getItems().get(i).getQuantity(), movement.getQuantity());
        }
    }

    @Test
    @DisplayName("Na efetivação sem saldo suficiente: 409 e nada é gravado, nem o pagamento, e a venda segue em aberto")
    void shouldNotWriteAnythingWhenStockIsShortOnCompletion() {
        Sale sale = existingSale(SaleStatus.OPEN);
        lockedStock.addAll(List.of(row(rice, "1"), row(beans, "1")));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> pay(sale, 9797L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("'Arroz 5kg' (pedido 2, disponível 1)"), error.getReason());
        assertEquals(SaleStatus.OPEN, sale.getStatus());
        assertNothingWritten();
    }

    @Test
    @DisplayName("Deve retornar 400 sem forma de pagamento, com valor zero, negativo, ausente ou acima do que falta pagar")
    void shouldRejectInvalidPayment() {
        Sale sale = existingSale(SaleStatus.OPEN, PaymentStatus.PAID);

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.registerPayment(store.getUuid(), sale.getUuid(), new PaymentRequest(null, 100L)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> pay(sale, 0L));
        assertStatus(HttpStatus.BAD_REQUEST, () -> pay(sale, -1L));
        assertStatus(HttpStatus.BAD_REQUEST, () -> pay(sale, null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> pay(sale, 8798L)); // faltam 8797
        assertNothingWritten();
    }

    @Test
    @DisplayName("Venda concluída ou cancelada não recebe pagamento nem é cancelada (409)")
    void shouldOnlyChangeOpenSales() {
        for (SaleStatus status : List.of(SaleStatus.COMPLETED, SaleStatus.CANCELED)) {
            Sale sale = existingSale(status);
            assertStatus(HttpStatus.CONFLICT, () -> pay(sale, 1L));
            assertStatus(HttpStatus.CONFLICT, () -> service.cancelSale(store.getUuid(), sale.getUuid()));
            assertEquals(status, sale.getStatus());
        }
        assertNothingWritten();
    }

    @Test
    @DisplayName("Cancelar a venda em aberto estorna os pagamentos aceitos (o recusado fica como está) e não mexe no estoque")
    void shouldCancelOpenSale() {
        Sale sale = existingSale(SaleStatus.OPEN, PaymentStatus.PAID, PaymentStatus.FAILED);

        Sale result = service.cancelSale(store.getUuid(), sale.getUuid());

        assertEquals(SaleStatus.CANCELED, result.getStatus());
        assertEquals(List.of(PaymentStatus.REFUNDED, PaymentStatus.FAILED), result.getPayments().stream().map(Payment::getStatus).toList());
        assertEquals(0L, result.paidInCents());
        verify(movementDao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 404 quando a venda não existe na loja (ou é de outra loja), e trava a venda ao pagar ou cancelar")
    void shouldReturnNotFoundAndLockWhenChanging() {
        UUID unknown = UUID.randomUUID();
        when(dao.findInStore(store, unknown, false)).thenReturn(Optional.empty());
        when(dao.findInStore(store, unknown, true)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.findByUuid(store.getUuid(), unknown));
        assertThrows(EntityNotFoundException.class,
                () -> service.registerPayment(store.getUuid(), unknown, new PaymentRequest(PaymentMethod.CASH, 100L)));
        assertThrows(EntityNotFoundException.class, () -> service.cancelSale(store.getUuid(), unknown));

        verify(dao).findInStore(store, unknown, false);
        verify(dao, times(2)).findInStore(store, unknown, true);
        assertNothingWritten();
    }
}
