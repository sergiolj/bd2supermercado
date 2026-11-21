package br.edu.ucsal.bd2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.edu.ucsal.bd2.controller.SaleController;
import br.edu.ucsal.bd2.controller.StoreHeader;
import br.edu.ucsal.bd2.dto.request.PaymentRequest;
import br.edu.ucsal.bd2.dto.request.SaleItemRequest;
import br.edu.ucsal.bd2.dto.request.SaleRequest;
import br.edu.ucsal.bd2.model.Payment;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.model.SaleItem;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.service.SaleService;
import br.edu.ucsal.bd2.util.PaymentMethod;
import br.edu.ucsal.bd2.util.PaymentStatus;
import br.edu.ucsal.bd2.util.SaleStatus;
import jakarta.persistence.EntityNotFoundException;

// Testa só a camada web. O fluxo da venda (estoque, transação, travas) é testado contra o PostgreSQL em SaleServiceIntegrationTest.
@WebMvcTest(SaleController.class)
class SaleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private SaleService service;

    private final UUID storeUuid = UUID.randomUUID();
    private final UUID sellerUuid = UUID.randomUUID();
    private final UUID productUuid = UUID.randomUUID();

    // ids internos preenchidos de propósito: os testes garantem que NUNCA aparecem na resposta.
    private Sale sale(SaleStatus status, PaymentStatus... payments) {
        Staff seller = new Staff();
        seller.setId(1L);
        seller.setUuid(sellerUuid);
        seller.setName("Caixa Ana");

        Product product = new Product();
        product.setId(2L);
        product.setUuid(productUuid);
        product.setName("Feijão a granel");

        Sale sale = new Sale();
        sale.setId(3L);
        sale.setUuid(UUID.randomUUID());
        sale.setSeller(seller);
        sale.setStatus(status);
        sale.setClientTaxNumber("12345678909");
        sale.setTotalAmountInCents(4999L);
        sale.setCreatedAt(Instant.parse("2026-11-21T13:00:00Z"));
        sale.setUpdatedAt(Instant.parse("2026-11-21T13:05:00Z"));

        SaleItem item = new SaleItem();
        item.setId(4L);
        item.setSale(sale);
        item.setProduct(product);
        item.setQuantity(new BigDecimal("1.250"));
        item.setUnitPriceInCents(3999L);
        sale.getItems().add(item);

        for (PaymentStatus paymentStatus : payments) {
            Payment payment = new Payment();
            payment.setId(5L);
            payment.setUuid(UUID.randomUUID());
            payment.setSale(sale);
            payment.setMethod(PaymentMethod.PIX);
            payment.setAmountInCents(2000L);
            payment.setStatus(paymentStatus);
            payment.setPaidAt(Instant.parse("2026-11-21T13:04:00Z"));
            sale.getPayments().add(payment);
        }
        return sale;
    }

    private SaleRequest request() {
        return new SaleRequest(sellerUuid, "123.456.789-09", List.of(new SaleItemRequest(productUuid, new BigDecimal("1.25"))));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    @DisplayName("Deve abrir a venda e responder 201 com itens, total e o que falta pagar, sem ids internos")
    void shouldOpenSale() throws Exception {
        Sale sale = sale(SaleStatus.OPEN);
        when(service.openSale(eq(storeUuid), any(SaleRequest.class))).thenReturn(sale);

        mockMvc.perform(post("/api/sale").header(StoreHeader.NAME, storeUuid).contentType(MediaType.APPLICATION_JSON).content(json(request())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").value(sale.getUuid().toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.sellerUuid").value(sellerUuid.toString()))
                .andExpect(jsonPath("$.sellerName").value("Caixa Ana"))
                .andExpect(jsonPath("$.items[0].productUuid").value(productUuid.toString()))
                .andExpect(jsonPath("$.items[0].quantity").value(1.25))
                .andExpect(jsonPath("$.items[0].totalInCents").value(4999)) // 1,25 x 3999 = 4998,75 -> 4999
                .andExpect(jsonPath("$.totalAmountInCents").value(4999))
                .andExpect(jsonPath("$.paidAmountInCents").value(0))
                .andExpect(jsonPath("$.remainingAmountInCents").value(4999))
                .andExpect(jsonPath("$.payments").isEmpty())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.items[0].id").doesNotExist());

        verify(service).openSale(storeUuid, request());
    }

    @Test
    @DisplayName("Deve responder 400 sem o header X-Store-Id, sem chamar o service")
    void shouldRequireStoreHeader() throws Exception {
        mockMvc.perform(post("/api/sale").contentType(MediaType.APPLICATION_JSON).content(json(request())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/sale/" + UUID.randomUUID()))
                .andExpect(status().isBadRequest());

        verify(service, never()).openSale(any(), any());
        verify(service, never()).findByUuid(any(), any());
    }

    @Test
    @DisplayName("Deve registrar o pagamento e responder 200 com a venda; o pagamento que quita mostra a venda concluída")
    void shouldRegisterPayment() throws Exception {
        Sale partial = sale(SaleStatus.OPEN, PaymentStatus.PAID);
        Sale completed = sale(SaleStatus.COMPLETED, PaymentStatus.PAID, PaymentStatus.PAID);
        completed.getPayments().get(1).setAmountInCents(2999L);
        PaymentRequest payment = new PaymentRequest(PaymentMethod.PIX, 2000L);
        when(service.registerPayment(eq(storeUuid), eq(partial.getUuid()), any(PaymentRequest.class))).thenReturn(partial);
        when(service.registerPayment(eq(storeUuid), eq(completed.getUuid()), any(PaymentRequest.class))).thenReturn(completed);

        mockMvc.perform(post("/api/sale/" + partial.getUuid() + "/payment").header(StoreHeader.NAME, storeUuid)
                        .contentType(MediaType.APPLICATION_JSON).content(json(payment)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.paidAmountInCents").value(2000))
                .andExpect(jsonPath("$.remainingAmountInCents").value(2999))
                .andExpect(jsonPath("$.payments[0].method").value("PIX"))
                .andExpect(jsonPath("$.payments[0].status").value("PAID"))
                .andExpect(jsonPath("$.payments[0].id").doesNotExist());
        verify(service).registerPayment(storeUuid, partial.getUuid(), payment);

        mockMvc.perform(post("/api/sale/" + completed.getUuid() + "/payment").header(StoreHeader.NAME, storeUuid)
                        .contentType(MediaType.APPLICATION_JSON).content(json(payment)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.paidAmountInCents").value(4999))
                .andExpect(jsonPath("$.remainingAmountInCents").value(0));
    }

    @Test
    @DisplayName("Deve responder 409 quando falta estoque para efetivar e 404 para venda inexistente, com o motivo no corpo")
    void shouldMapConflictAndNotFound() throws Exception {
        UUID saleUuid = UUID.randomUUID();
        String reason = "Estoque insuficiente na loja 'Loja Pituba' para: 'Feijão a granel' (pedido 2, disponível 1).";
        when(service.registerPayment(eq(storeUuid), eq(saleUuid), any(PaymentRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, reason));
        when(service.findByUuid(storeUuid, saleUuid))
                .thenThrow(new EntityNotFoundException("Venda com uuid: " + saleUuid + " não encontrada na loja 'Loja Pituba'."));

        mockMvc.perform(post("/api/sale/" + saleUuid + "/payment").header(StoreHeader.NAME, storeUuid)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new PaymentRequest(PaymentMethod.CASH, 4999L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Operação não permitida no estado atual."))
                .andExpect(jsonPath("$.message").value(reason))
                .andExpect(jsonPath("$.path").value("/api/sale/" + saleUuid + "/payment"))
                .andExpect(jsonPath("$.trace").doesNotExist());
        mockMvc.perform(get("/api/sale/" + saleUuid).header(StoreHeader.NAME, storeUuid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Registro não encontrado."))
                .andExpect(jsonPath("$.message").value("Venda com uuid: " + saleUuid + " não encontrada na loja 'Loja Pituba'."));
    }

    @Test
    @DisplayName("Deve responder 400 com o motivo da regra no corpo, no formato padrão de erro")
    void shouldMapBadRequestReason() throws Exception {
        when(service.openSale(eq(storeUuid), any(SaleRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "A venda precisa de pelo menos um item."));

        mockMvc.perform(post("/api/sale").header(StoreHeader.NAME, storeUuid).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SaleRequest(sellerUuid, null, List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Requisição inválida."))
                .andExpect(jsonPath("$.message").value("A venda precisa de pelo menos um item."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Deve cancelar a venda e responder 200 com os pagamentos estornados e nada a pagar")
    void shouldCancelSale() throws Exception {
        Sale canceled = sale(SaleStatus.CANCELED, PaymentStatus.REFUNDED);
        when(service.cancelSale(storeUuid, canceled.getUuid())).thenReturn(canceled);

        mockMvc.perform(post("/api/sale/" + canceled.getUuid() + "/cancel").header(StoreHeader.NAME, storeUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.payments[0].status").value("REFUNDED"))
                .andExpect(jsonPath("$.paidAmountInCents").value(0))
                .andExpect(jsonPath("$.remainingAmountInCents").value(0));
    }

    @Test
    @DisplayName("Deve buscar a venda pelo uuid")
    void shouldFindSale() throws Exception {
        Sale sale = sale(SaleStatus.COMPLETED, PaymentStatus.FAILED);
        when(service.findByUuid(storeUuid, sale.getUuid())).thenReturn(sale);

        mockMvc.perform(get("/api/sale/" + sale.getUuid()).header(StoreHeader.NAME, storeUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientTaxNumber").value("12345678909"))
                .andExpect(jsonPath("$.items[0].productName").value("Feijão a granel"))
                .andExpect(jsonPath("$.payments[0].status").value("FAILED"))
                .andExpect(jsonPath("$.paidAmountInCents").value(0)); // recusado não conta
    }
}
