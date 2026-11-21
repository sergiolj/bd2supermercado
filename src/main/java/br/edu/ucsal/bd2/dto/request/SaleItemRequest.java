package br.edu.ucsal.bd2.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

// Um item da venda. O preço não vem do cliente: é o preço vigente do produto quando a venda é aberta.
@Schema(description = "Item da venda")
public record SaleItemRequest(
        @Schema(description = "uuid do produto (ativo e com preço vigente)", example = "837b085d-09d3-b72c-da4f-0ef73b8f05a7",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID productUuid,
        @Schema(description = "Quantidade vendida, na unidade do produto. Até 3 casas decimais; produto por unidade (UNIT) exige número inteiro.",
                example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal quantity
) {
}
