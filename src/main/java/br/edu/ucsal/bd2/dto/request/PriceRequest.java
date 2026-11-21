package br.edu.ucsal.bd2.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

// Corpo do POST/PUT de preço. O produto vem na URL.
@Schema(description = "Novo preço do produto")
public record PriceRequest(
        @Schema(description = "Preço em centavos (R$ 28,99 = 2899). Para produto vendido por peso ou volume, é o preço da unidade de medida do produto.",
                example = "2899", minimum = "1", maximum = "1000000000", requiredMode = Schema.RequiredMode.REQUIRED)
        Long valueInCents
) {
}
