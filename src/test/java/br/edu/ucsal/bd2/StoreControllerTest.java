package br.edu.ucsal.bd2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.edu.ucsal.bd2.controller.StoreController;
import br.edu.ucsal.bd2.dto.request.StoreRequest;
import br.edu.ucsal.bd2.model.Address;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.service.StoreService;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

// Testa só a camada web: o JSON vira StoreRequest, a resposta traz o gerente por uuid e nome, e os erros viram o status certo.
@WebMvcTest(StoreController.class)
class StoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private StoreService service;

    private final UUID managerUuid = UUID.randomUUID();

    // id interno preenchido de propósito: os testes garantem que ele NUNCA aparece na resposta.
    private Store newStore(boolean withManager) {
        Address address = new Address();
        address.setCity("Salvador");

        Store store = new Store();
        store.setId(1L);
        store.setUuid(UUID.randomUUID());
        store.setName("Mercado Central");
        store.setAddress(address);
        if (withManager) {
            Staff manager = new Staff();
            manager.setId(9L);
            manager.setUuid(managerUuid);
            manager.setName("Marina Teixeira");
            store.setManager(manager);
        }
        return store;
    }

    private StoreRequest request() {
        return new StoreRequest("Mercado Central", managerUuid, null, null, null, "Salvador", "BA", null);
    }

    @Test
    @DisplayName("Deve criar a loja e responder 201 com o gerente (uuid e nome) e sem ids internos")
    void shouldCreateStore() throws Exception {
        Store store = newStore(true);
        when(service.createStore(any(StoreRequest.class))).thenReturn(store);

        mockMvc.perform(post("/api/store").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").value(store.getUuid().toString()))
                .andExpect(jsonPath("$.managerUuid").value(managerUuid.toString()))
                .andExpect(jsonPath("$.managerName").value("Marina Teixeira"))
                .andExpect(jsonPath("$.city").value("Salvador"))
                .andExpect(jsonPath("$.id").doesNotExist());

        verify(service).createStore(request());
    }

    @Test
    @DisplayName("Deve responder com gerente nulo quando a loja não tem gerente")
    void shouldReturnStoreWithoutManager() throws Exception {
        Store store = newStore(false);
        when(service.findByUuid(store.getUuid())).thenReturn(store);

        mockMvc.perform(get("/api/store/" + store.getUuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerUuid").doesNotExist())
                .andExpect(jsonPath("$.managerName").doesNotExist());
    }

    @Test
    @DisplayName("Ao criar, deve responder 409 (nome repetido) e 400 (loja nova com gerente)")
    void shouldReturnConflictAndBadRequestOnCreate() throws Exception {
        when(service.createStore(any(StoreRequest.class)))
                .thenThrow(new EntityExistsException("Já existe uma loja com esse nome."))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uma loja nova ainda não tem funcionários."));

        mockMvc.perform(post("/api/store").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/store").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Ao atualizar, deve responder 404 (gerente inexistente) e 409 (gerente que não trabalha na loja)")
    void shouldReturnNotFoundAndConflictOnUpdate() throws Exception {
        UUID storeUuid = UUID.randomUUID();
        when(service.updateStore(eq(storeUuid), any(StoreRequest.class)))
                .thenThrow(new EntityNotFoundException("Funcionário com uuid: " + managerUuid + " não encontrado."))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "O funcionário não trabalha nessa loja."));

        mockMvc.perform(put("/api/store/" + storeUuid).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/store/" + storeUuid).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Deve listar com paginação (padrão 0/20)")
    void shouldListWithPagination() throws Exception {
        when(service.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(newStore(true)), PageRequest.of(1, 5), 6));

        mockMvc.perform(get("/api/store").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Mercado Central"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(6));
        verify(service).findAll(PageRequest.of(1, 5));
    }

    @Test
    @DisplayName("Deve atualizar a loja")
    void shouldUpdateStore() throws Exception {
        Store store = newStore(true);
        when(service.updateStore(eq(store.getUuid()), any(StoreRequest.class))).thenReturn(store);

        mockMvc.perform(put("/api/store/" + store.getUuid()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mercado Central"));
    }

    @Test
    @DisplayName("Deve apagar com 204 e responder 404 (loja inexistente) e 409 (loja com funcionários ativos)")
    void shouldDeleteStore() throws Exception {
        UUID uuid = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID staffed = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Loja com uuid: " + missing + " não encontrada.")).when(service).deleteStore(missing);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "A loja tem 3 funcionário(s) ativo(s).")).when(service).deleteStore(staffed);

        mockMvc.perform(delete("/api/store/" + uuid)).andExpect(status().isNoContent());
        verify(service).deleteStore(uuid);
        mockMvc.perform(delete("/api/store/" + missing)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/store/" + staffed)).andExpect(status().isConflict());
    }
}
