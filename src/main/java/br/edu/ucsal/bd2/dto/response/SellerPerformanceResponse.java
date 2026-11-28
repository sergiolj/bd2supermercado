package br.edu.ucsal.bd2.dto.response;

import br.edu.ucsal.bd2.util.StaffRole;

public record SellerPerformanceResponse(
        String seller,
        StaffRole role,
        String store,
        Long sales,
        Long revenueInCents,
        Long averageTicketInCents
) {

    // Usado pela expressão "SELECT new" da JPQL: AVG sempre devolve Double, arredondado para o centavo aqui.
    public SellerPerformanceResponse(String seller, StaffRole role, String store, Long sales, Long revenueInCents,
            Double averageTicket) {
        this(seller, role, store, sales, revenueInCents, Math.round(averageTicket));
    }
}
