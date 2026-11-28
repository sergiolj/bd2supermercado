package br.edu.ucsal.bd2.dto.response;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CategoryStockFlowResponse(
        String category,
        BigDecimal purchased,
        BigDecimal sold,
        BigDecimal lost,
        BigDecimal lossRatePercent
) {

    // Usado pela expressão "SELECT new" da JPQL: a taxa de perda (perdido / comprado) é calculada aqui.
    // Sem compra no período a taxa fica nula, em vez de dividir por zero.
    public CategoryStockFlowResponse(String category, BigDecimal purchased, BigDecimal sold, BigDecimal lost) {
        this(category, purchased, sold, lost, lossRate(purchased, lost));
    }

    private static BigDecimal lossRate(BigDecimal purchased, BigDecimal lost) {
        if (purchased.signum() == 0) {
            return null;
        }
        return lost.multiply(BigDecimal.valueOf(100)).divide(purchased, 2, RoundingMode.HALF_UP);
    }
}
