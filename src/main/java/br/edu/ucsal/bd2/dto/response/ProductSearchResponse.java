package br.edu.ucsal.bd2.dto.response;

import java.util.UUID;

import br.edu.ucsal.bd2.util.MeasurementType;

// Linha do resultado da busca de produtos (Criteria 1), já com o preço vigente.
// Montada direto na consulta (cb.construct), então não carrega entidades nem dispara consultas extras.
public record ProductSearchResponse(
		UUID uuid,
		String name,
		String barcode,
		MeasurementType unitOfMeasure,
		String categoryName,
		Long currentPriceInCents
) { }
