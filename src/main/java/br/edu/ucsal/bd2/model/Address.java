package br.edu.ucsal.bd2.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Não tem tabela própria: as colunas ficam dentro da tabela de quem o usa (customer)
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class Address {

    private String street;

    private String number;

    private String district;

    private String city;

    @Column(length = 2)
    private String state;

    @Column(length = 8)
    private String zipCode;
}
