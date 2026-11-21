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

    private Staff manager() {
        Staff staff = new Staff();
        staff.setUuid(UUID.randomUUID());
        staff.setName("Marina");
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
    @DisplayName("Deve criar a loja com gerente, nome sem espaços extras e endereço normalizado")
    void shouldCreateStoreWithManager() {
        Staff manager = manager();

        Store result = service.createStore(request("  Mercado Central  ", manager.getUuid()));

        assertEquals("Mercado Central", result.getName());
        assertSame(manager, result.getManager());
        assertEquals("BA", result.getAddress().getState());
        assertEquals("41830000", result.getAddress().getZipCode());
        verify(dao).save(result);
    }

    @Test
    @DisplayName("Deve criar a loja sem gerente e sem endereço")
    void shouldCreateStoreWithoutManager() {
        Store result = service.createStore(new StoreRequest("Mercado Central", null, null, null, null, null, null, null));

        assertNull(result.getManager());
        verify(dao).save(result);
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
        UUID unknown = UUID.randomUUID();
        when(staffDao.findActiveByUuid(unknown)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.createStore(request("Loja", unknown)));

        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Não deve criar nem atualizar loja com um gerente que já gerencia outra loja")
    void shouldRejectManagerOfAnotherStore() {
        Staff manager = manager();
        when(dao.existsByManager(manager)).thenReturn(true);

        assertThrows(EntityExistsException.class, () -> service.createStore(request("Loja Nova", manager.getUuid())));
        verify(dao, never()).save(any());

        UUID storeUuid = UUID.randomUUID();
        existing(storeUuid, null);
        when(dao.existsByManagerInAnotherStore(manager, storeUuid)).thenReturn(true);
        assertThrows(EntityExistsException.class, () -> service.updateStore(storeUuid, request("Loja Antiga", manager.getUuid())));
    }

    @Test
    @DisplayName("Deve atualizar a loja, trocar o gerente e permitir tirá-lo")
    void shouldUpdateStore() {
        UUID storeUuid = UUID.randomUUID();
        Store store = existing(storeUuid, null);
        Staff manager = manager();

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
    @DisplayName("Deve apagar de forma lógica e soltar o gerente")
    void shouldSoftDeleteAndReleaseManager() {
        UUID storeUuid = UUID.randomUUID();
        Store store = existing(storeUuid, manager());

        service.deleteStore(storeUuid);

        assertNotNull(store.getDeletedAt());
        assertNull(store.getManager());
        verify(dao).update(store);
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
