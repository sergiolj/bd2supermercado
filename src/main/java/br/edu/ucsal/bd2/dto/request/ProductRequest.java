package br.edu.ucsal.bd2.dto.request;

import java.math.BigDecimal;

public record ProductRequest(
		String name,
		String description,
		String barCode,
		BigDecimal price
		) {

}
