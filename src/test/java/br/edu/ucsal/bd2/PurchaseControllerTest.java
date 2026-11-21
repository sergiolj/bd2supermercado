package br.edu.ucsal.bd2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

import br.edu.ucsal.bd2.controller.PurchaseController;
import br.edu.ucsal.bd2.controller.StoreHeader;
import br.edu.ucsal.bd2.dto.request.PurchaseRequest;
import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Supplier;
import br.edu.ucsal.bd2.service.PurchaseService;
import br.edu.ucsal.bd2.util.MovementType;
import jakarta.persistence.EntityNotFoundException;

// Testa só a camada web. O efeito no estoque e o estorno são testados contra o PostgreSQL em PurchaseServiceIntegrationTest.
@WebMvcTest(PurchaseController.class)
class PurchaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private PurchaseService service;

    private final UUID storeUuid = UUID.randomUUID();
    private final UUID productUuid = UUID.randomUUID();
    private final UUID supplierUuid = UUID.randomUUID();

    // ids internos preenchidos de propósito: os testes garantem que NUNCA aparecem na resposta.
    private InventoryMovement newPurchase() {
        Product product = new Product();
        product.setId(1L);
        product.setUuid(productUuid);
        product.setName("Arroz 5kg");

        Supplier supplier = new Supplier();
        supplier.setId(2L);
        supplier.setUuid(supplierUuid);
        supplier.setName("Distribuidora Bahia");

        InventoryMovement purchase = new InventoryMovement();
        purchase.setId(3L);
        purchase.setUuid(UUID.randomUUID());
        purchase.setType(MovementType.PURCHASE);
        purchase.setProduct(product);
        purchase.setSupplier(supplier);
        purchase.setQuantity(new BigDecimal("12.500"));
        purchase.setUnitCostInCents(899L);
        purchase.setBatchExpiresAt(LocalDate.of(2027, 3, 31));
        purchase.setCreatedAt(Instant.parse("2026-11-20T15:00:00Z"));
        return purchase;
    }

    private PurchaseRequest request() {
        return new PurchaseRequest(productUuid, supplierUuid, new BigDecimal("12.5"), 899L, LocalDate.of(2027, 3, 31));
    }

    @Test
    @DisplayName("Deve lançar a compra e responder 201 com o total (quantidade x custo, arredondado) e sem ids internos")
    void shouldRegisterPurchase() throws Exception {
        InventoryMovement purchase = newPurchase();
        when(service.registerPurchase(eq(storeUuid), any(PurchaseRequest.class))).thenReturn(purchase);

        mockMvc.perform(post("/api/purchase").header(StoreHeader.NAME, storeUuid).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").value(purchase.getUuid().toString()))
                .andExpect(jsonPath("$.productName").value("Arroz 5kg"))
                .andExpect(jsonPath("$.supplierName").value("Distribuidora Bahia"))
                .andExpect(jsonPath("$.quantity").value(12.5))
                .andExpect(jsonPath("$.totalCostInCents").value(11238)) // 12,5 x 899 = 11237,5 -> 11238
                .andExpect(jsonPath("$.batchExpiresAt").value("2027-03-31"))
                .andExpect(jsonPath("$.id").doesNotExist());

        verify(service).registerPurchase(storeUuid, request());
    }

    @Test
    @DisplayName("Deve responder 400 para dados inválidos e 404 para produto ou fornecedor inexistente")
    void shouldReturnBadRequestAndNotFound() throws Exception {
        when(service.registerPurchase(eq(storeUuid), any(PurchaseRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "A quantidade deve ser maior que zero."))
                .thenThrow(new EntityNotFoundException("Fornecedor com uuid: " + supplierUuid + " não encontrado."));

        mockMvc.perform(post("/api/purchase").header(StoreHeader.NAME, storeUuid).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/purchase").header(StoreHeader.NAME, storeUuid).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Deve listar com paginação (padrão 0/20) e buscar por uuid")
    void shouldListAndFind() throws Exception {
        InventoryMovement purchase = newPurchase();
        when(service.findAll(eq(storeUuid), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(purchase), PageRequest.of(0, 20), 1));
        when(service.findByUuid(storeUuid, purchase.getUuid())).thenReturn(purchase);

        mockMvc.perform(get("/api/purchase").header(StoreHeader.NAME, storeUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].unitCostInCents").value(899))
                .andExpect(jsonPath("$.totalElements").value(1));
        verify(service).findAll(storeUuid, PageRequest.of(0, 20));

        mockMvc.perform(get("/api/purchase/" + purchase.getUuid()).header(StoreHeader.NAME, storeUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productUuid").value(productUuid.toString()));
    }

    @Test
    @DisplayName("Deve apagar com 204 e responder 404 (inexistente) e 409 (estoque já consumido)")
    void shouldDeletePurchase() throws Exception {
        UUID ok = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID consumed = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Compra não encontrada.")).when(service).deletePurchase(storeUuid, missing);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "parte da quantidade já saiu do estoque")).when(service).deletePurchase(storeUuid, consumed);

        mockMvc.perform(delete("/api/purchase/" + ok).header(StoreHeader.NAME, storeUuid)).andExpect(status().isNoContent());
        verify(service).deletePurchase(storeUuid, ok);
        mockMvc.perform(delete("/api/purchase/" + missing).header(StoreHeader.NAME, storeUuid)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/purchase/" + consumed).header(StoreHeader.NAME, storeUuid)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Deve responder 400 sem chamar o service quando falta o header X-Store-Id ou ele não é um uuid")
    void shouldRequireStoreHeader() throws Exception {
        mockMvc.perform(get("/api/purchase")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/purchase").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/purchase/" + UUID.randomUUID())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/purchase").header(StoreHeader.NAME, "loja-1")).andExpect(status().isBadRequest());

        verify(service, never()).findAll(any(), any());
        verify(service, never()).registerPurchase(any(), any());
        verify(service, never()).deletePurchase(any(), any());
    }

    @Test
    @DisplayName("Deve responder 404 quando a loja do header não existe")
    void shouldReturnNotFoundForUnknownStore() throws Exception {
        when(service.findAll(eq(storeUuid), any(Pageable.class)))
                .thenThrow(new EntityNotFoundException("Loja com uuid: " + storeUuid + " não encontrada."));

        mockMvc.perform(get("/api/purchase").header(StoreHeader.NAME, storeUuid)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Não deve existir edição: a movimentação é imutável (PUT responde 405)")
    void shouldNotAllowUpdate() throws Exception {
        mockMvc.perform(put("/api/purchase/" + UUID.randomUUID()).header(StoreHeader.NAME, storeUuid).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isMethodNotAllowed());
    }
}
