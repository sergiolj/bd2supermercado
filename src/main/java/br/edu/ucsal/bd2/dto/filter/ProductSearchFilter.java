package br.edu.ucsal.bd2.dto.filter;

import java.util.UUID;

import br.edu.ucsal.bd2.util.MeasurementType;

// Filtros opcionais da busca de produtos (Criteria 1). Qualquer campo nulo é ignorado na consulta.
// Preços em centavos, igual ao resto do sistema (Price.valueInCents).
public record ProductSearchFilter(
		String name,
		UUID categoryUuid,
		Long minPriceInCents,
		Long maxPriceInCents,
		MeasurementType unitOfMeasure
) { }
