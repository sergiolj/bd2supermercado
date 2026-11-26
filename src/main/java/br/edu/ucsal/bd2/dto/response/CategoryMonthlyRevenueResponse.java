package br.edu.ucsal.bd2.dto.response;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CategoryMonthlyRevenueResponse(
        String category,
        Integer year,
        Integer month,
        BigDecimal quantity,
        Long revenueInCents
) {

    // Usado pela expressão "SELECT new" da JPQL: SUM(quantidade x preço) chega como BigDecimal (a quantidade é
    // decimal por causa dos itens a granel) e é arredondado para o centavo aqui.
    public CategoryMonthlyRevenueResponse(String category, Integer year, Integer month, BigDecimal quantity,
            BigDecimal revenue) {
        this(category, year, month, quantity, revenue.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }
}
