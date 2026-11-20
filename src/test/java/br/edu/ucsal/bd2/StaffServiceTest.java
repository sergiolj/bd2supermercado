package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import br.edu.ucsal.bd2.dao.StaffDAO;
import br.edu.ucsal.bd2.dto.request.StaffRequest;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.service.StaffService;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

// Teste de unidade puro: sem Spring e sem banco, só o service com o DAO simulado.
class StaffServiceTest {

    private final StaffDAO dao = mock(StaffDAO.class);
    private final StaffService service = new StaffService(dao);

    // Monta um funcionário válido; cada teste muda só o CPF, o perfil ou o endereço.
    private StaffRequest request(String taxNumber, StaffRole role, String state, String zipCode) {
        return new StaffRequest(taxNumber, "  Marina Teixeira ", role, "marina@mercado.com", "71999990000",
                "Rua das Flores", "10", "Pituba", "Salvador", state, zipCode);
    }

    private StaffRequest valid() {
        return request("529.982.247-25", StaffRole.MANAGER, "ba", "41830-000");
    }

    private Staff existing(UUID uuid) {
        Staff staff = new Staff();
        staff.setUuid(uuid);
        staff.setTaxNumber("52998224725");
        staff.setName("Marina");
        staff.setRole(StaffRole.CASHIER);
        when(dao.findActiveByUuid(uuid)).thenReturn(Optional.of(staff));
        when(dao.update(staff)).thenReturn(staff);
        return staff;
    }

    @Test
    @DisplayName("Deve salvar o funcionário com CPF só com números, nome sem espaços extras, UF maiúscula e CEP sem traço")
    void shouldCreateStaff() {
        Staff result = service.createStaff(valid());

        assertEquals("52998224725", result.getTaxNumber());
        assertEquals("Marina Teixeira", result.getName());
        assertEquals(StaffRole.MANAGER, result.getRole());
        assertEquals("BA", result.getAddress().getState());
        assertEquals("41830000", result.getAddress().getZipCode());
        verify(dao).save(result);
    }

    @Test
    @DisplayName("Deve aceitar o cadastro só com CPF, nome e perfil (e transformar texto vazio em nulo)")
    void shouldCreateStaffWithOnlyRequiredFields() {
        Staff result = service.createStaff(new StaffRequest("52998224725", "Marina", StaffRole.CASHIER, "  ", "", null, null, null, null, null, null));

        assertNull(result.getEmail());
        assertNull(result.getPhone());
        assertNotNull(result.getAddress());
        assertNull(result.getAddress().getState());
    }

    @Test
    @DisplayName("Não deve salvar quando o CPF já existe (de funcionário ou de cliente)")
    void shouldRejectDuplicateTaxNumber() {
        when(dao.existsByTaxNumber("52998224725")).thenReturn(true);

        assertThrows(EntityExistsException.class, () -> service.createStaff(valid()));

        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 400 para CPF que não tem 11 dígitos ou tem letras")
    void shouldRejectInvalidTaxNumber() {
        for (String taxNumber : new String[] {null, "", "1234567890", "123456789012", "abcdefghijk", "5299822472a"}) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> service.createStaff(request(taxNumber, StaffRole.CASHIER, "BA", null)));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }
        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 400 sem perfil, com nome vazio, UF ou CEP inválidos")
    void shouldRejectInvalidFields() {
        assertBadRequest(request("52998224725", null, "BA", null));
        assertBadRequest(new StaffRequest("52998224725", "   ", StaffRole.CASHIER, null, null, null, null, null, null, null, null));
        assertBadRequest(new StaffRequest("52998224725", null, StaffRole.CASHIER, null, null, null, null, null, null, null, null));
        assertBadRequest(request("52998224725", StaffRole.CASHIER, "BAH", null));
        assertBadRequest(request("52998224725", StaffRole.CASHIER, "B1", null));
        assertBadRequest(request("52998224725", StaffRole.CASHIER, "BA", "1234"));
        assertBadRequest(new StaffRequest("52998224725", "x".repeat(256), StaffRole.CASHIER, null, null, null, null, null, null, null, null));
        verify(dao, never()).save(any());
    }

    private void assertBadRequest(StaffRequest request) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.createStaff(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    @DisplayName("Deve atualizar o funcionário")
    void shouldUpdateStaff() {
        UUID uuid = UUID.randomUUID();
        Staff staff = existing(uuid);

        Staff result = service.updateStaff(uuid, request("111.444.777-35", StaffRole.ADMIN, "BA", null));

        assertEquals("11144477735", result.getTaxNumber());
        assertEquals(StaffRole.ADMIN, staff.getRole());
        assertEquals("Marina Teixeira", staff.getName());
    }

    @Test
    @DisplayName("Não deve atualizar para o CPF de outra pessoa")
    void shouldRejectUpdateToAnotherPersonsTaxNumber() {
        UUID uuid = UUID.randomUUID();
        existing(uuid);
        when(dao.existsByTaxNumberInAnotherPerson("11144477735", uuid)).thenReturn(true);

        assertThrows(EntityExistsException.class, () -> service.updateStaff(uuid, request("11144477735", StaffRole.CASHIER, null, null)));
    }

    @Test
    @DisplayName("Deve retornar 404 ao buscar, atualizar ou apagar funcionário inexistente ou já apagado")
    void shouldReturnNotFound() {
        UUID uuid = UUID.randomUUID();
        when(dao.findActiveByUuid(uuid)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.findByUuid(uuid));
        assertThrows(EntityNotFoundException.class, () -> service.updateStaff(uuid, valid()));
        assertThrows(EntityNotFoundException.class, () -> service.deleteStaff(uuid));
    }

    @Test
    @DisplayName("Deve apagar de forma lógica: preenche deletedAt e mantém o registro")
    void shouldSoftDelete() {
        UUID uuid = UUID.randomUUID();
        Staff staff = existing(uuid);

        service.deleteStaff(uuid);

        assertNotNull(staff.getDeletedAt());
        verify(dao).update(staff);
    }

    @Test
    @DisplayName("Não deve apagar quem gerencia uma loja")
    void shouldNotDeleteStoreManager() {
        UUID uuid = UUID.randomUUID();
        Staff staff = existing(uuid);
        when(dao.managesStore(staff)).thenReturn(true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.deleteStaff(uuid));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertNull(staff.getDeletedAt());
        verify(dao, never()).update(any());
    }
}
