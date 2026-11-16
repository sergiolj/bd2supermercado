package br.edu.ucsal.bd2.dto.response;

import java.util.UUID;

import br.edu.ucsal.bd2.model.Product;


// O id interno do banco nunca sai pela API: o produto e a categoria sao identificados pelo uuid.
public record ProductResponse (
		UUID uuid,
		String name,
		String description,
		UUID categoryUuid,
		String categoryName

){

	public static ProductResponse convertToDTO(Product product) {
		return new ProductResponse(
				product.getUuid(),
				product.getName(),
				product.getDescription(),
				product.getCategory().getUuid(),
				product.getCategory().getName()
				);
	}

}
