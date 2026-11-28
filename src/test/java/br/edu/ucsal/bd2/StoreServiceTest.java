package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.StaffDAO;
import br.edu.ucsal.bd2.dao.StoreDAO;
import br.edu.ucsal.bd2.dto.request.StoreRequest;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.service.StoreService;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

// Teste de unidade puro: sem Spring e sem banco, só o service com os DAOs simulados.
class StoreServiceTest {

    private final StoreDAO dao = mock(StoreDAO.class);
    private final StaffDAO staffDao = mock(StaffDAO.class);
    private final StoreService service = new StoreService(dao, staffDao);

    private StoreRequest request(String name, UUID managerUuid) {
        return new StoreRequest(name, managerUuid, "Av. Manoel Dias", "1200", "Pituba", "Salvador", "ba", "41830-000");
    }

    // Funcionário ativo que trabalha em `workplace` (nulo = ADMIN, que é da rede).
    private Staff staffOf(Store workplace) {
        Staff staff = new Staff();
        staff.setUuid(UUID.randomUUID());
        staff.setName("Marina");
        staff.setRole(workplace == null ? StaffRole.ADMIN : StaffRole.MANAGER);
        staff.setStore(workplace);
        when(staffDao.findActiveByUuid(staff.getUuid())).thenReturn(Optional.of(staff));
        return staff;
    }

    private Store existing(UUID uuid, Staff manager) {
        Store store = new Store();
        store.setUuid(uuid);
        store.setName("Loja Antiga");
        store.setManager(manager);
        when(dao.findActiveByUuid(uuid)).thenReturn(Optional.of(store));
        when(dao.update(store)).thenReturn(store);
        return store;
    }

    @Test
    @DisplayName("Deve criar a loja sem gerente, com nome sem espaços extras e endereço normalizado")
    void shouldCreateStore() {
        Store result = service.createStore(request("  Mercado Central  ", null));

        assertEquals("Mercado Central", result.getName());
        assertNull(result.getManager());
        assertEquals("BA", result.getAddress().getState());
        assertEquals("41830000", result.getAddress().getZipCode());
        verify(dao).save(result);
    }

    @Test
    @DisplayName("Deve criar a loja sem endereço")
    void shouldCreateStoreWithoutAddress() {
        Store result = service.createStore(new StoreRequest("Mercado Central", null, null, null, null, null, null, null));

        assertNull(result.getManager());
        verify(dao).save(result);
    }

    @Test
    @DisplayName("Loja nova não pode ter gerente (400): ainda não há funcionário trabalhando nela")
    void shouldRejectManagerOnCreate() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createStore(request("Loja Nova", UUID.randomUUID())));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(dao, never()).save(any());
        verifyNoInteractions(staffDao);
    }

    @Test
    @DisplayName("Não deve criar com nome vazio (400) nem com nome que já existe (409)")
    void shouldValidateName() {
        ResponseStatusException blank = assertThrows(ResponseStatusException.class,
                () -> service.createStore(new StoreRequest("   ", null, null, null, null, null, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatusCode());

        when(dao.existsByName("Mercado Central")).thenReturn(true);
        assertThrows(EntityExistsException.class,
                () -> service.createStore(new StoreRequest("Mercado Central", null, null, null, null, null, null, null)));

        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 404 quando o gerente não existe ou foi apagado")
    void shouldRejectUnknownManager() {
        UUID storeUuid = UUID.randomUUID();
        existing(storeUuid, null);
        UUID unknown = UUID.randomUUID();
        when(staffDao.findActiveByUuid(unknown)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.updateStore(storeUuid, request("Loja Antiga", unknown)));

        verify(dao, never()).update(any());
    }

    @Test
    @DisplayName("Não deve aceitar caixa ou estoquista como gerente")
    void shouldRejectStaffWhoIsNotManager() {
        UUID storeUuid = UUID.randomUUID();
        Store store = existing(storeUuid, null);

        for (StaffRole role : new StaffRole[] {StaffRole.CASHIER, StaffRole.STOCKIST}) {
            Staff candidate = staffOf(store);
            candidate.setRole(role);

            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> service.updateStore(storeUuid, request("Loja Antiga", candidate.getUuid())));

            assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        }

        assertNull(store.getManager());
        verify(dao, never()).update(any());
    }

    @Test
    @DisplayName("O gerente precisa trabalhar na loja: funcionário de outra loja ou ADMIN (sem loja) é 409")
    void shouldRejectManagerFromOutsideTheStore() {
        UUID storeUuid = UUID.randomUUID();
        Store store = existing(storeUuid, null);
        Store other = new Store();
        other.setUuid(UUID.randomUUID());

        for (Staff outsider : new Staff[] {staffOf(other), staffOf(null)}) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> service.updateStore(storeUuid, request("Loja Antiga", outsider.getUuid())));
            assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        }
        assertNull(store.getManager());
        verify(dao, never()).update(any());
    }

    @Test
    @DisplayName("Deve atualizar a loja, definir como gerente um funcionário dela e permitir tirá-lo")
    void shouldUpdateStore() {
        UUID storeUuid = UUID.randomUUID();
        Store store = existing(storeUuid, null);
        Staff manager = staffOf(store);

        service.updateStore(storeUuid, request("Loja Renomeada", manager.getUuid()));
        assertEquals("Loja Renomeada", store.getName());
        assertSame(manager, store.getManager());

        service.updateStore(storeUuid, request("Loja Renomeada", null));
        assertNull(store.getManager());
    }

    @Test
    @DisplayName("Não deve renomear para o nome de outra loja")
    void shouldRejectNameOfAnotherStore() {
        UUID storeUuid = UUID.randomUUID();
        existing(storeUuid, null);
        when(dao.existsByNameInAnotherStore("Loja Dois", storeUuid)).thenReturn(true);

        assertThrows(EntityExistsException.class, () -> service.updateStore(storeUuid, request("Loja Dois", null)));
    }

    @Test
    @DisplayName("Deve apagar de forma lógica a loja sem funcionários ativos")
    void shouldSoftDelete() {
        UUID storeUuid = UUID.randomUUID();
        Store store = existing(storeUuid, null);
        when(staffDao.countActiveByStore(store)).thenReturn(0L);

        service.deleteStore(storeUuid);

        assertNotNull(store.getDeletedAt());
        verify(dao).update(store);
    }

    @Test
    @DisplayName("Não deve apagar a loja que ainda tem funcionários ativos (409)")
    void shouldNotDeleteStoreWithActiveStaff() {
        UUID storeUuid = UUID.randomUUID();
        Store store = existing(storeUuid, null);
        when(staffDao.countActiveByStore(store)).thenReturn(3L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.deleteStore(storeUuid));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertNull(store.getDeletedAt());
        verify(dao, never()).update(any());
    }

    @Test
    @DisplayName("Deve retornar 404 ao buscar, atualizar ou apagar loja inexistente")
    void shouldReturnNotFound() {
        UUID uuid = UUID.randomUUID();
        when(dao.findActiveByUuid(uuid)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.findByUuid(uuid));
        assertThrows(EntityNotFoundException.class, () -> service.updateStore(uuid, request("Loja", null)));
        assertThrows(EntityNotFoundException.class, () -> service.deleteStore(uuid));
    }
}
