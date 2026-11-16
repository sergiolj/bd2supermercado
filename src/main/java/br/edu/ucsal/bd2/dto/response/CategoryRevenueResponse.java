package br.edu.ucsal.bd2.dto.response;

import java.math.BigDecimal;

// Uma linha do relatório de faturamento por categoria (Criteria 3).
// quantitySold soma unidades e quilos juntos (é a soma de SaleItem.quantity), por isso é BigDecimal.
public record CategoryRevenueResponse(
		String categoryName,
		Long salesCount,
		BigDecimal quantitySold,
		Long revenueInCents
) { }
