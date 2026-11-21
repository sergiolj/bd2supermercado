package br.edu.ucsal.bd2.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.util.SaleStatus;

// Venda com itens e pagamentos. O id interno do banco nunca sai pela API: venda, vendedor e produtos vão pelo uuid.
// paidAmountInCents soma só os pagamentos aceitos (PAID). remainingAmountInCents é o que falta pagar numa venda em
// aberto; venda concluída ou cancelada não espera mais pagamento, então ali é zero.
public record SaleResponse(
        UUID uuid,
        SaleStatus status,
        UUID sellerUuid,
        String sellerName,
        String clientTaxNumber,
        List<SaleItemResponse> items,
        Long totalAmountInCents,
        Long paidAmountInCents,
        Long remainingAmountInCents,
        List<PaymentResponse> payments,
        Instant createdAt,
        Instant updatedAt
) {

    public static SaleResponse convertToDTO(Sale sale) {
        return new SaleResponse(
                sale.getUuid(),
                sale.getStatus(),
                sale.getSeller().getUuid(),
                sale.getSeller().getName(),
                sale.getClientTaxNumber(),
                sale.getItems().stream().map(SaleItemResponse::convertToDTO).toList(),
                sale.getTotalAmountInCents(),
                sale.paidInCents(),
                sale.getStatus() == SaleStatus.OPEN ? sale.remainingInCents() : 0L,
                sale.getPayments().stream().map(PaymentResponse::convertToDTO).toList(),
                sale.getCreatedAt(),
                sale.getUpdatedAt()
        );
    }
}
