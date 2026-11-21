package br.edu.ucsal.bd2.dto.response;

import java.math.BigDecimal;

public record ProductSalesResponse(
        String product,
        BigDecimal quantity,
        Long sales
) {
}

