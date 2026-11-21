package br.edu.ucsal.bd2.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

import br.edu.ucsal.bd2.model.SaleItem;

// Um item da venda. totalInCents é quantidade x preço unitário, arredondado para o centavo (meio para cima).
public record SaleItemResponse(
        UUID productUuid,
        String productName,
        BigDecimal quantity,
        Long unitPriceInCents,
        Long totalInCents
) {

    public static SaleItemResponse convertToDTO(SaleItem item) {
        return new SaleItemResponse(
                item.getProduct().getUuid(),
                item.getProduct().getName(),
                item.getQuantity(),
                item.getUnitPriceInCents(),
                item.totalInCents()
        );
    }
}
