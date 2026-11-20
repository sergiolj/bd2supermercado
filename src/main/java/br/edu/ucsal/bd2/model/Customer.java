package br.edu.ucsal.bd2.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

// Todos os dados vêm de Person; a tabela customer guarda só o id
@Entity
@NoArgsConstructor
@Table(name = "customer")
public class Customer extends Person {

}

