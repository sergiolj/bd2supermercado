package br.edu.ucsal.bd2.dto.request;

import br.edu.ucsal.bd2.model.Supplier;

// Corpo do POST/PUT de fornecedor. taxNumber é o CNPJ: aceita com ou sem pontuação (o service guarda só os dígitos).
public record SupplierRequest(
		String name,
		String taxNumber,
		String email,
		String phone
		) {

	public static SupplierRequest convertToDTO(Supplier supplier) {
		return new SupplierRequest(
				supplier.getName(),
				supplier.getTaxNumber(),
				supplier.getEmail(),
				supplier.getPhone()
				);
		}
}
