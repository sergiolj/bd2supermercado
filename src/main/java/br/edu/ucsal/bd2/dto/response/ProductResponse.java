package br.edu.ucsal.bd2.dto.response;

import java.util.UUID;

import br.edu.ucsal.bd2.model.Product;


public record ProductResponse (
		Long id,
		UUID uuid,
		String name,
		String description,
		String categoryName

){

	public static ProductResponse convertToDTO(Product product) {
		return new ProductResponse(
				product.getId(),
				product.getUuid(),
				product.getName(),
				product.getDescription(),
				product.getCategory().getName()
				);
	}

}
