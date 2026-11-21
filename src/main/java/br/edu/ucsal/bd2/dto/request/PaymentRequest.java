package br.edu.ucsal.bd2.dto.request;

import br.edu.ucsal.bd2.util.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;

// Corpo do POST de pagamento de uma venda. O pagamento é registrado como recebido (PAID) no momento do lançamento.
@Schema(description = "Pagamento recebido de uma venda em aberto")
public record PaymentRequest(
        @Schema(description = "Forma de pagamento", example = "PIX", requiredMode = Schema.RequiredMode.REQUIRED)
        PaymentMethod method,
        @Schema(description = "Valor recebido em centavos (R$ 43,90 = 4390). Não pode passar do que falta pagar.",
                example = "4390", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Long amountInCents
) {
}
