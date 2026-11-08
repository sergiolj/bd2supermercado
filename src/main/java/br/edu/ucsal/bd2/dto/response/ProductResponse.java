package br.edu.ucsal.bd2.dto.response;

import br.edu.ucsal.bd2.model.Product;


public record ProductResponse (
		Long id,
		String name,
		Double weight,
		String brandName
		
){

	public static ProductResponse convertToDTO(Product product) {
		return new ProductResponse(
				product.getId(), 
				product.getName(),
				product.getWeight(),
				product.getBrand().getName()
				);
	}

}
