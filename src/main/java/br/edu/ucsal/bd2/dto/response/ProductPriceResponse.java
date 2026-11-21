package br.edu.ucsal.bd2.dto.response;

public record ProductPriceResponse(
        String product,
        String category,
        Long priceInCents
) {
}

