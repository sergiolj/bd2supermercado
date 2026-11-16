package br.edu.ucsal.bd2.dto.request;

import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.util.MeasurementType;

public record ProductRequest(
		String name,
		Long categoryId,
		String barcode,
		String description,
		MeasurementType unitOfMeasure
		) {

	public static ProductRequest convertToDTO(Product product) {
		return new ProductRequest(
				product.getName(),
				product.getCategory().getId(),
				product.getBarcode(),
				product.getDescription(),
				product.getUnitOfMeasure()
				);
		}
}
