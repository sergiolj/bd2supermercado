package br.edu.ucsal.bd2.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

// Corpo do POST de compra. A data do lançamento é a do momento em que ele é registrado.
@Schema(description = "Lançamento de compra de reposição de estoque")
public record PurchaseRequest(
        @Schema(description = "uuid do produto comprado (ativo)", example = "837b085d-09d3-b72c-da4f-0ef73b8f05a7",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID productUuid,
        @Schema(description = "uuid do fornecedor (ativo)", example = "f1bb09d7-685f-595d-44dc-2ab4806ed716",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID supplierUuid,
        @Schema(description = "Quantidade comprada, na unidade do produto. Até 3 casas decimais; produto por unidade (UNIT) exige número inteiro.",
                example = "24", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal quantity,
        @Schema(description = "Custo de cada unidade de medida do produto, em centavos (R$ 18,50 = 1850)",
                example = "1850", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Long unitCostInCents,
        @Schema(description = "Validade do lote (opcional). Não pode ser uma data que já passou.", example = "2027-03-31")
        LocalDate batchExpiresAt
) {
}
