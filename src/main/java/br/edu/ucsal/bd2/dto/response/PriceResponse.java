package br.edu.ucsal.bd2.dto.response;

import java.time.Instant;

import br.edu.ucsal.bd2.model.Price;

// Um preço do histórico do produto. validUntil nulo significa que é o preço vigente.
public record PriceResponse(
        Long valueInCents,
        Instant validFrom,
        Instant validUntil
) {

    public static PriceResponse convertToDTO(Price price) {
        return new PriceResponse(
                price.getValueInCents(),
                price.getCreatedAt(),
                price.getDeletedAt()
        );
    }
}
