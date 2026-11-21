package br.edu.ucsal.bd2.dto.filter;

import java.time.LocalDate;
import java.util.UUID;

import br.edu.ucsal.bd2.util.SaleStatus;

// Filtros opcionais da busca de vendas (Criteria 2). Qualquer campo nulo é ignorado na consulta.
// storeUuid vem do header X-Store-Id: sem ele, a busca é na rede inteira.
// "from" e "to" são datas do calendário da loja (America/Bahia), ambas inclusivas.
public record SaleSearchFilter(
		UUID storeUuid,
		LocalDate from,
		LocalDate to,
		UUID sellerUuid,
		SaleStatus status,
		Long minTotalInCents,
		Long maxTotalInCents
) { }
