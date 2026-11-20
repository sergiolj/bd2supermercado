package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.CustomerDAO;
import br.edu.ucsal.bd2.dto.request.CustomerRequest;
import br.edu.ucsal.bd2.model.Customer;
import br.edu.ucsal.bd2.service.CustomerService;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

// Teste de unidade puro: sem Spring e sem banco, só o service com o DAO simulado.
class CustomerServiceTest {

    private final CustomerDAO dao = mock(CustomerDAO.class);
    private final CustomerService service = new CustomerService(dao);

    // Monta um cliente válido; cada teste muda só o CPF ou a UF.
    private CustomerRequest request(String taxNumber, String state) {
        return new CustomerRequest(taxNumber, "  Maria Souza ", "maria@email.com", "71999990000",
                "Rua das Flores", "10", "Pituba", "Salvador", state, "41810000");
    }

    @Test
    @DisplayName("Deve salvar o cliente com o CPF só com números e o nome sem espaços extras")
    void shouldCreateCustomer() {
        when(dao.existsByTaxNumber("12345678900")).thenReturn(false);

        Customer result = service.createCustomer(request("123.456.789-00", "BA"));

        assertEquals("12345678900", result.getTaxNumber());
        assertEquals("Maria Souza", result.getName());
        assertEquals("Salvador", result.getAddress().getCity());
        verify(dao).save(result);
    }

    @Test
    @DisplayName("Não deve salvar quando o CPF já existe")
    void shouldRejectDuplicateTaxNumber() {
        when(dao.existsByTaxNumber("12345678900")).thenReturn(true);

        assertThrows(EntityExistsException.class,
                () -> service.createCustomer(request("12345678900", "BA")));

        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 400 quando o CPF não tem 11 dígitos")
    void shouldRejectInvalidTaxNumber() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createCustomer(request("1234567890", "BA")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 400 quando a UF não tem 2 letras")
    void shouldRejectInvalidState() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createCustomer(request("12345678900", "BAH")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 404 quando o cliente não existe")
    void shouldNotFindCustomer() {
        UUID uuid = UUID.randomUUID();
        when(dao.findActiveByUuid(uuid)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.findByUuid(uuid));
    }

    @Test
    @DisplayName("Deve preencher deletedAt em vez de apagar o registro (soft delete)")
    void shouldSoftDeleteCustomer() {
        UUID uuid = UUID.randomUUID();
        Customer customer = new Customer();
        when(dao.findActiveByUuid(uuid)).thenReturn(Optional.of(customer));

        service.deleteCustomer(uuid);

        assertNotNull(customer.getDeletedAt());
        verify(dao).update(customer);
    }
}

