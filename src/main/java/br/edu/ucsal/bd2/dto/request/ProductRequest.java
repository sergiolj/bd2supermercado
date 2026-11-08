package br.edu.ucsal.bd2.dto.request;

import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.util.MeasurementType;

public record ProductRequest(
		String name,
		Long brandId,
		Long categoryId,
		String barCode,
		Double weight,
		MeasurementType unitType
		) {

	public static ProductRequest convertToDTO(Product product) {
		return new ProductRequest(
				product.getName(),
				product.getBrand().getId(),
				product.getCategory().getId(),
				product.getBarCode(),
				product.getWeight(),
				product.getUnitType()
				);
		}
}
