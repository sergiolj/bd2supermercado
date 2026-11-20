package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.PriceDAO;
import br.edu.ucsal.bd2.dto.request.PriceRequest;
import br.edu.ucsal.bd2.model.Price;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.service.PriceService;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

// Teste de unidade puro: sem Spring e sem banco, só o service com o DAO simulado.
// O travamento da linha do produto e a concorrência são testados contra o PostgreSQL em PriceServiceIntegrationTest.
class PriceServiceTest {

    private final PriceDAO dao = mock(PriceDAO.class);
    private final PriceService service = new PriceService(dao);

    private final UUID productUuid = UUID.randomUUID();
    private final Product product = product();

    private Product product() {
        Product p = new Product();
        p.setUuid(productUuid);
        p.setName("Arroz 5kg");
        when(dao.findActiveProduct(productUuid, true)).thenReturn(Optional.of(p));
        when(dao.findActiveProduct(productUuid, false)).thenReturn(Optional.of(p));
        // O Hibernate preenche createdAt ao gravar; o simulado faz o mesmo para o service poder encadear o histórico.
        doAnswer(call -> {
            Price saved = call.getArgument(0);
            saved.setCreatedAt(Instant.parse("2026-11-20T15:00:00Z"));
            return saved;
        }).when(dao).save(any(Price.class));
        return p;
    }

    private Price current(long cents) {
        Price price = new Price();
        price.setProduct(product);
        price.setValueInCents(cents);
        price.setCreatedAt(Instant.parse("2026-01-01T10:00:00Z"));
        when(dao.findCurrent(product)).thenReturn(Optional.of(price));
        return price;
    }

    @Test
    @DisplayName("Deve definir o primeiro preço do produto")
    void shouldCreateFirstPrice() {
        when(dao.findCurrent(product)).thenReturn(Optional.empty());

        Price result = service.createPrice(productUuid, new PriceRequest(2899L));

        assertEquals(2899L, result.getValueInCents());
        assertSame(product, result.getProduct());
        assertNull(result.getDeletedAt());
        verify(dao).save(result);
    }

    @Test
    @DisplayName("Não deve criar preço quando o produto já tem um vigente (409: use o PUT)")
    void shouldRejectCreateWhenCurrentExists() {
        current(2899L);

        assertThrows(EntityExistsException.class, () -> service.createPrice(productUuid, new PriceRequest(3000L)));

        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve trocar o preço: grava o novo e fecha o antigo exatamente no início do novo")
    void shouldReplaceCurrentPrice() {
        Price old = current(2899L);

        Price result = service.updatePrice(productUuid, new PriceRequest(3199L));

        assertEquals(3199L, result.getValueInCents());
        assertNull(result.getDeletedAt());
        assertEquals(result.getCreatedAt(), old.getDeletedAt());
        verify(dao).save(result);
        verify(dao).update(old);
    }

    @Test
    @DisplayName("Não deve mexer no histórico quando o valor é igual ao vigente")
    void shouldKeepHistoryWhenValueIsTheSame() {
        Price old = current(2899L);

        Price result = service.updatePrice(productUuid, new PriceRequest(2899L));

        assertSame(old, result);
        assertNull(old.getDeletedAt());
        verify(dao, never()).save(any());
        verify(dao, never()).update(any());
    }

    @Test
    @DisplayName("Deve retornar 404 ao alterar produto sem preço vigente (use o POST)")
    void shouldRejectUpdateWithoutCurrentPrice() {
        when(dao.findCurrent(product)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.updatePrice(productUuid, new PriceRequest(100L)));
    }

    @Test
    @DisplayName("Deve tirar o produto de venda fechando o preço vigente")
    void shouldWithdrawFromSale() {
        Price old = current(2899L);

        service.deletePrice(productUuid);

        assertNotNull(old.getDeletedAt());
        verify(dao).update(old);
        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 404 ao apagar o preço de produto que já está sem preço")
    void shouldRejectDeleteWithoutCurrentPrice() {
        when(dao.findCurrent(product)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.deletePrice(productUuid));
    }

    @Test
    @DisplayName("Deve retornar 400 para preço nulo, zero, negativo ou acima do limite")
    void shouldRejectInvalidValues() {
        when(dao.findCurrent(product)).thenReturn(Optional.empty());

        for (Long value : new Long[] {null, 0L, -1L, 1_000_000_001L}) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> service.createPrice(productUuid, new PriceRequest(value)));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }
        assertThrows(ResponseStatusException.class, () -> service.createPrice(productUuid, null));
        verify(dao, never()).save(any());

        // O limite é inclusivo.
        assertEquals(1_000_000_000L, service.createPrice(productUuid, new PriceRequest(1_000_000_000L)).getValueInCents());
    }

    @Test
    @DisplayName("Deve retornar 404 quando o produto não existe ou foi apagado")
    void shouldReturnNotFoundForUnknownProduct() {
        UUID unknown = UUID.randomUUID();
        when(dao.findActiveProduct(unknown, false)).thenReturn(Optional.empty());
        when(dao.findActiveProduct(unknown, true)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.findCurrent(unknown));
        assertThrows(EntityNotFoundException.class, () -> service.createPrice(unknown, new PriceRequest(100L)));
        assertThrows(EntityNotFoundException.class, () -> service.updatePrice(unknown, new PriceRequest(100L)));
        assertThrows(EntityNotFoundException.class, () -> service.deletePrice(unknown));
    }
}
