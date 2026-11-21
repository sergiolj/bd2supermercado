package br.edu.ucsal.bd2.dto.request;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

// Corpo do POST de venda. A loja vem do header X-Store-Id; a venda nasce em aberto (OPEN), à espera do pagamento.
@Schema(description = "Abertura de uma venda com os seus itens")
public record SaleRequest(
        @Schema(description = "uuid do vendedor: funcionário ativo da loja da venda", example = "5f0c7e2a-1b3d-4c5e-8f9a-0b1c2d3e4f5a",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sellerUuid,
        @Schema(description = "CPF (11 dígitos) ou CNPJ (14 dígitos) na nota, opcional. Aceita com ou sem pontuação.",
                example = "123.456.789-09")
        String clientTaxNumber,
        @Schema(description = "Itens da venda: pelo menos um, e cada produto aparece uma vez só",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<SaleItemRequest> items
) {
}
