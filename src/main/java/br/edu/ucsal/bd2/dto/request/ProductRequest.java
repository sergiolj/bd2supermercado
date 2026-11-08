package br.edu.ucsal.bd2.dto.request;

import java.math.BigDecimal;

import br.edu.ucsal.bd2.model.Product;

public record ProductRequest(
		String name,
		String description,
		String barCode,
		BigDecimal price
		) {

	public static ProductRequest convertToDTO(Product product) {
		return new ProductRequest(
				product.getName(),
				product.getDescription(),
				product.getBarCode(),
				product.getPrice()
				);
		}
}
