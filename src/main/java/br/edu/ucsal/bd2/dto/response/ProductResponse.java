package br.edu.ucsal.bd2.dto.response;

import java.math.BigDecimal;

import br.edu.ucsal.bd2.model.Product;


public record ProductResponse (
		Long id,
		String name,
		BigDecimal price
){

	public static ProductResponse convertToDTO(Product product) {
		return new ProductResponse(
				product.getIdLong(), 
				product.getName(), 
				product.getPrice()
				);
	}

}
