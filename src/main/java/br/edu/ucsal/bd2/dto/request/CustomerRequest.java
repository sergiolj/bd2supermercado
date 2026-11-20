package br.edu.ucsal.bd2.dto.request;

public record CustomerRequest(
        String taxNumber,
        String name,
        String email,
        String phone,
        String street,
        String number,
        String district,
        String city,
        String state,
        String zipCode
) {
}
