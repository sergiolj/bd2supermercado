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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.edu.ucsal.bd2.controller.PriceController;
import br.edu.ucsal.bd2.dto.request.PriceRequest;
import br.edu.ucsal.bd2.model.Price;
import br.edu.ucsal.bd2.service.PriceService;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

// Testa só a camada web: o produto vem na URL, o corpo traz só o valor, e o histórico sai paginado.
@WebMvcTest(PriceController.class)
class PriceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PriceService service;

    private final UUID productUuid = UUID.randomUUID();
    private final String url = "/api/product/" + productUuid + "/price";

    private Price price(long cents, Instant from, Instant until) {
        Price price = new Price();
        price.setId(5L);
        price.setValueInCents(cents);
        price.setCreatedAt(from);
        price.setDeletedAt(until);
        return price;
    }

    @Test
    @DisplayName("Deve devolver o preço vigente (validUntil nulo) sem expor o id")
    void shouldReturnCurrentPrice() throws Exception {
        when(service.findCurrent(productUuid)).thenReturn(price(2899, Instant.parse("2026-01-01T10:00:00Z"), null));

        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueInCents").value(2899))
                .andExpect(jsonPath("$.validFrom").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.validUntil").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    @DisplayName("Deve responder 404 quando o produto não existe ou não tem preço vigente")
    void shouldReturnNotFound() throws Exception {
        when(service.findCurrent(productUuid)).thenThrow(new EntityNotFoundException("O produto 'Arroz' não tem preço vigente."));

        mockMvc.perform(get(url))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("O produto 'Arroz' não tem preço vigente."));
    }

    @Test
    @DisplayName("Deve listar o histórico paginado (padrão 0/20) com o preço vigente e os substituídos")
    void shouldListHistory() throws Exception {
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T10:00:00Z");
        when(service.findHistory(eq(productUuid), any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(price(3199, t2, null), price(2899, t1, t2)), PageRequest.of(0, 20), 2));

        mockMvc.perform(get(url + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].valueInCents").value(3199))
                .andExpect(jsonPath("$.content[0].validUntil").doesNotExist())
                .andExpect(jsonPath("$.content[1].validUntil").value("2026-06-01T10:00:00Z"))
                .andExpect(jsonPath("$.totalElements").value(2));
        verify(service).findHistory(productUuid, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("Deve definir o primeiro preço (201) e responder 409 quando já existe um vigente")
    void shouldCreatePrice() throws Exception {
        when(service.createPrice(eq(productUuid), any(PriceRequest.class)))
                .thenReturn(price(2899, Instant.parse("2026-01-01T10:00:00Z"), null))
                .thenThrow(new EntityExistsException("O produto 'Arroz' já tem preço vigente. Use PUT para alterá-lo."));

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"valueInCents\":2899}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valueInCents").value(2899));
        verify(service).createPrice(productUuid, new PriceRequest(2899L));

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"valueInCents\":2899}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Deve alterar o preço vigente")
    void shouldUpdatePrice() throws Exception {
        when(service.updatePrice(eq(productUuid), any(PriceRequest.class)))
                .thenReturn(price(3199, Instant.parse("2026-06-01T10:00:00Z"), null));

        mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("{\"valueInCents\":3199}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueInCents").value(3199));
    }

    @Test
    @DisplayName("Deve tirar o produto de venda com 204 e responder 404 quando já está sem preço")
    void shouldDeletePrice() throws Exception {
        UUID withoutPrice = UUID.randomUUID();
        doThrow(new EntityNotFoundException("não tem preço vigente")).when(service).deletePrice(withoutPrice);

        mockMvc.perform(delete(url)).andExpect(status().isNoContent());
        verify(service).deletePrice(productUuid);
        mockMvc.perform(delete("/api/product/" + withoutPrice + "/price")).andExpect(status().isNotFound());
    }
}
