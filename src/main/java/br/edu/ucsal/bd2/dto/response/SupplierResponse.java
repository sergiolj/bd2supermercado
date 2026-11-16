package br.edu.ucsal.bd2.dto.response;

import java.util.UUID;

import br.edu.ucsal.bd2.model.Supplier;

// O id interno do banco nunca sai pela API: o fornecedor é identificado pelo uuid.
public record SupplierResponse(
		UUID uuid,
		String name,
		String taxNumber,
		String email,
		String phone
		) {

	public static SupplierResponse convertToDTO(Supplier supplier) {
		return new SupplierResponse(
				supplier.getUuid(),
				supplier.getName(),
				supplier.getTaxNumber(),
				supplier.getEmail(),
				supplier.getPhone()
				);
	}
}
