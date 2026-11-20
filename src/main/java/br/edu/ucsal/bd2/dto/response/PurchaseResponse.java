package br.edu.ucsal.bd2.dto.response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.model.Supplier;

// O id interno do banco nunca sai pela API: a compra, o produto e o fornecedor são identificados pelo uuid.
// totalCostInCents é quantidade x custo unitário, arredondado para o centavo (meio para cima), como no seed.
public record PurchaseResponse(
        UUID uuid,
        UUID productUuid,
        String productName,
        UUID supplierUuid,
        String supplierName,
        BigDecimal quantity,
        Long unitCostInCents,
        Long totalCostInCents,
        LocalDate batchExpiresAt,
        Instant createdAt
) {

    public static PurchaseResponse convertToDTO(InventoryMovement purchase) {
        Supplier supplier = purchase.getSupplier();
        Long unitCost = purchase.getUnitCostInCents();

        return new PurchaseResponse(
                purchase.getUuid(),
                purchase.getProduct().getUuid(),
                purchase.getProduct().getName(),
                supplier == null ? null : supplier.getUuid(),
                supplier == null ? null : supplier.getName(),
                purchase.getQuantity(),
                unitCost,
                unitCost == null ? null : purchase.getQuantity()
                        .multiply(BigDecimal.valueOf(unitCost))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact(),
                purchase.getBatchExpiresAt(),
                purchase.getCreatedAt()
        );
    }
}
