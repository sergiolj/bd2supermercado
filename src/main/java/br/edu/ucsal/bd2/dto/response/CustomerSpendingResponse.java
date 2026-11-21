package br.edu.ucsal.bd2.dto.response;

public record CustomerSpendingResponse(
        String name,
        String city,
        Long sales,
        Long totalInCents
) {
}
