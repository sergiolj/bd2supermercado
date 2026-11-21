package br.edu.ucsal.bd2.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.model.Address;

// Monta e valida o endereço dos cadastros que o recebem (funcionário e loja). As colunas de UF e CEP têm tamanho
// fixo: sem essa checagem o banco recusaria e a API daria 500. Todos os campos são opcionais.
public final class AddressBuilder {

    private AddressBuilder() {
    }

    public static Address build(String street, String number, String district, String city, String state, String zipCode) {
        Address address = new Address();
        address.setStreet(TextRules.optional(street, "Rua"));
        address.setNumber(TextRules.optional(number, "Número"));
        address.setDistrict(TextRules.optional(district, "Bairro"));
        address.setCity(TextRules.optional(city, "Cidade"));
        address.setState(validState(state));
        address.setZipCode(validZipCode(zipCode));
        return address;
    }

    private static String validState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String trimmed = state.strip();
        if (!trimmed.matches("[A-Za-z]{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UF deve ter 2 letras.");
        }
        return trimmed.toUpperCase();
    }

    // Aceita "41810-000" ou "41810000" e guarda só os 8 dígitos.
    private static String validZipCode(String zipCode) {
        if (zipCode == null || zipCode.isBlank()) {
            return null;
        }
        String digits = zipCode.strip().replace("-", "");
        if (!digits.matches("\\d{8}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CEP deve ter 8 dígitos.");
        }
        return digits;
    }
}
