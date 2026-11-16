package br.edu.ucsal.bd2.dto.response;

import java.time.Instant;
import java.util.UUID;

import br.edu.ucsal.bd2.util.SaleStatus;

// Linha do resultado da busca de vendas (Criteria 2). O nome do vendedor vem no mesmo SELECT (join),
// em vez de carregar Staff por venda (evita o problema N+1).
public record SaleSummaryResponse(
		UUID uuid,
		Instant createdAt,
		SaleStatus status,
		Long totalAmountInCents,
		String sellerName
) { }
