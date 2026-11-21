package br.edu.ucsal.bd2.dto.response;

import java.time.Instant;
import java.util.UUID;

import br.edu.ucsal.bd2.model.Payment;
import br.edu.ucsal.bd2.util.PaymentMethod;
import br.edu.ucsal.bd2.util.PaymentStatus;

public record PaymentResponse(
        UUID uuid,
        PaymentMethod method,
        Long amountInCents,
        PaymentStatus status,
        Instant paidAt
) {

    public static PaymentResponse convertToDTO(Payment payment) {
        return new PaymentResponse(
                payment.getUuid(),
                payment.getMethod(),
                payment.getAmountInCents(),
                payment.getStatus(),
                payment.getPaidAt()
        );
    }
}
