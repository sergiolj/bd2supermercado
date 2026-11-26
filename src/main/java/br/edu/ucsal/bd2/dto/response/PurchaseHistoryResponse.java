package br.edu.ucsal.bd2.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.model.SaleItem;

public record PurchaseHistoryResponse(
        UUID sale,
        Instant createdAt,
        String store,
        Long totalInCents,
        List<Item> items
) {

    public record Item(
            String product,
            BigDecimal quantity,
            Long unitPriceInCents,
            Long totalInCents
    ) {
    }

    // Só navega pelo que a consulta trouxe com JOIN FETCH (loja, itens e produtos): nenhum SELECT extra.
    public static PurchaseHistoryResponse from(Sale sale) {
        List<Item> items = sale.getItems().stream()
                .map(PurchaseHistoryResponse::item)
                .toList();
        return new PurchaseHistoryResponse(sale.getUuid(), sale.getCreatedAt(), sale.getStore().getName(),
                sale.getTotalAmountInCents(), items);
    }

    private static Item item(SaleItem item) {
        return new Item(item.getProduct().getName(), item.getQuantity(), item.getUnitPriceInCents(),
                item.totalInCents());
    }
}
