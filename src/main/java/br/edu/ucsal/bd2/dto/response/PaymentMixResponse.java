package br.edu.ucsal.bd2.dto.response;

import br.edu.ucsal.bd2.util.PaymentMethod;

public record PaymentMixResponse(
        String store,
        PaymentMethod method,
        Long payments,
        Long totalInCents
) {
}
