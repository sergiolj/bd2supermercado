package br.edu.ucsal.bd2.dto.response;

import java.util.UUID;

public record InactiveCustomerResponse(
        UUID uuid,
        String name,
        String email,
        String phone,
        String city,
        String state
) {
}
