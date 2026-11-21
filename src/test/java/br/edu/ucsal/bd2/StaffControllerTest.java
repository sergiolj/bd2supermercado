package br.edu.ucsal.bd2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

import br.edu.ucsal.bd2.controller.StaffController;
import br.edu.ucsal.bd2.controller.StoreHeader;
import br.edu.ucsal.bd2.dto.request.StaffRequest;
import br.edu.ucsal.bd2.model.Address;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.service.StaffService;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

// Testa só a camada web: o JSON vira StaffRequest, a resposta sai no formato esperado e os erros viram o status certo.
@WebMvcTest(StaffController.class)
class StaffControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private StaffService service;

    private final UUID storeUuid = UUID.randomUUID();

    // id interno preenchido de propósito: os testes garantem que ele NUNCA aparece na resposta.
    private Staff newStaff() {
        Address address = new Address();
        address.setCity("Salvador");
        address.setState("BA");

        Store store = new Store();
        store.setId(5L);
        store.setUuid(storeUuid);
        store.setName("Loja Pituba");

        Staff staff = new Staff();
        staff.setId(1L);
        staff.setUuid(UUID.randomUUID());
        staff.setTaxNumber("52998224725");
        staff.setName("Marina Teixeira");
        staff.setRole(StaffRole.MANAGER);
        staff.setStore(store);
        staff.setEmail("marina@mercado.com");
        staff.setAddress(address);
        return staff;
    }

    private StaffRequest request() {
        return new StaffRequest("529.982.247-25", "Marina Teixeira", StaffRole.MANAGER, storeUuid, "marina@mercado.com",
                null, null, null, null, "Salvador", "BA", null);
    }

    @Test
    @DisplayName("Deve criar o funcionário e responder 201 com o perfil, a loja (uuid e nome) e sem ids internos")
    void shouldCreateStaff() throws Exception {
        Staff staff = newStaff();
        when(service.createStaff(any(StaffRequest.class))).thenReturn(staff);

        mockMvc.perform(post("/api/staff")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").value(staff.getUuid().toString()))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.storeUuid").value(storeUuid.toString()))
                .andExpect(jsonPath("$.storeName").value("Loja Pituba"))
                .andExpect(jsonPath("$.city").value("Salvador"))
                .andExpect(jsonPath("$.id").doesNotExist());

        verify(service).createStaff(request());
    }

    @Test
    @DisplayName("Deve responder 409 quando o CPF já existe e 400 quando o service recusa os dados")
    void shouldReturnConflictAndBadRequest() throws Exception {
        when(service.createStaff(any(StaffRequest.class)))
                .thenThrow(new EntityExistsException("Já existe um funcionário ou cliente com esse CPF."))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "CPF deve ter 11 dígitos."));

        mockMvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Deve responder 400 sem chamar o service quando o perfil não existe")
    void shouldRejectUnknownRole() throws Exception {
        mockMvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON)
                .content("{\"taxNumber\":\"52998224725\",\"name\":\"X\",\"role\":\"CEO\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).createStaff(any());
    }

    @Test
    @DisplayName("Deve listar com paginação (padrão 0/20) e limitar o tamanho da página a 100")
    void shouldListWithPagination() throws Exception {
        when(service.findAll(isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(newStaff()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/staff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Marina Teixeira"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").doesNotExist());
        verify(service).findAll(null, PageRequest.of(0, 20));

        mockMvc.perform(get("/api/staff").param("page", "-3").param("size", "5000")).andExpect(status().isOk());
        verify(service).findAll(null, PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("Com o header X-Store-Id, lista só os funcionários da loja; loja inexistente responde 404")
    void shouldListByStoreHeader() throws Exception {
        UUID missing = UUID.randomUUID();
        when(service.findAll(eq(storeUuid), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(newStaff()), PageRequest.of(0, 20), 1));
        when(service.findAll(eq(missing), any(Pageable.class))).thenThrow(new EntityNotFoundException("Loja com uuid: " + missing + " não encontrada."));

        mockMvc.perform(get("/api/staff").header(StoreHeader.NAME, storeUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].storeName").value("Loja Pituba"));
        verify(service).findAll(storeUuid, PageRequest.of(0, 20));

        mockMvc.perform(get("/api/staff").header(StoreHeader.NAME, missing)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Deve buscar por uuid e responder 404 com a mensagem quando não existe")
    void shouldFindByUuid() throws Exception {
        Staff staff = newStaff();
        UUID missing = UUID.randomUUID();
        when(service.findByUuid(staff.getUuid())).thenReturn(staff);
        when(service.findByUuid(missing)).thenThrow(new EntityNotFoundException("Funcionário com uuid: " + missing + " não encontrado."));

        mockMvc.perform(get("/api/staff/" + staff.getUuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxNumber").value("52998224725"));
        mockMvc.perform(get("/api/staff/" + missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Funcionário com uuid: " + missing + " não encontrado."));
    }

    @Test
    @DisplayName("Deve atualizar o funcionário")
    void shouldUpdateStaff() throws Exception {
        Staff staff = newStaff();
        when(service.updateStaff(eq(staff.getUuid()), any(StaffRequest.class))).thenReturn(staff);

        mockMvc.perform(put("/api/staff/" + staff.getUuid())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uuid").value(staff.getUuid().toString()));
    }

    @Test
    @DisplayName("Deve apagar com 204 e responder 409 quando o funcionário gerencia uma loja")
    void shouldDeleteStaff() throws Exception {
        UUID uuid = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Funcionário é gerente de uma loja"))
                .when(service).deleteStaff(manager);

        mockMvc.perform(delete("/api/staff/" + uuid)).andExpect(status().isNoContent());
        verify(service).deleteStaff(uuid);
        mockMvc.perform(delete("/api/staff/" + manager)).andExpect(status().isConflict());
    }
}
