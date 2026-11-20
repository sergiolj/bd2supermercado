package br.edu.ucsal.bd2.dto.response;

import java.util.UUID;

import br.edu.ucsal.bd2.model.Address;
import br.edu.ucsal.bd2.model.Customer;

public record CustomerResponse(
        UUID uuid,
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

    public static CustomerResponse convertToDTO(Customer customer) {
        // Se todas as colunas do endereço forem nulas, o Hibernate devolve address = null
        Address address = customer.getAddress();
        if (address == null) {
            address = new Address();
        }

        return new CustomerResponse(
                customer.getUuid(),
                customer.getTaxNumber(),
                customer.getName(),
                customer.getEmail(),
                customer.getPhone(),
                address.getStreet(),
                address.getNumber(),
                address.getDistrict(),
                address.getCity(),
                address.getState(),
                address.getZipCode()
        );
    }
}

