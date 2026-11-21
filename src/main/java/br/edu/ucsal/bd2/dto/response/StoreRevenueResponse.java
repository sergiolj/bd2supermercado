package br.edu.ucsal.bd2.dto.response;

public record StoreRevenueResponse(
        String store,
        String city,
        String state,
        Long sales,
        Long totalInCents
) {
}
