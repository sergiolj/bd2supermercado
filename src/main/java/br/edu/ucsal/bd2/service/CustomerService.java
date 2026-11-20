package br.edu.ucsal.bd2.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.CustomerDAO;
import br.edu.ucsal.bd2.dto.request.CustomerRequest;
import br.edu.ucsal.bd2.model.Address;
import br.edu.ucsal.bd2.model.Customer;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class CustomerService {

    private final CustomerDAO dao;

    public Page<Customer> findAll(Pageable pageable) {
        return dao.findActive(pageable);
    }

    public Customer findByUuid(UUID customerUuid) {
        Optional<Customer> customer = dao.findActiveByUuid(customerUuid);

        if (customer.isEmpty()) {
            throw new EntityNotFoundException("Cliente com uuid: " + customerUuid + " não encontrado.");
        }
        return customer.get();
    }

    @Transactional
    public Customer createCustomer(CustomerRequest dto) {
        String taxNumber = validateTaxNumber(dto.taxNumber());

        if (dao.existsByTaxNumber(taxNumber)) {
            throw new EntityExistsException("Já existe um cliente com esse CPF.");
        }

        Customer customer = new Customer();
        customer.setTaxNumber(taxNumber);
        fillData(customer, dto);
        dao.save(customer);
        return customer;
    }

    @Transactional
    public Customer updateCustomer(UUID customerUuid, CustomerRequest dto) {
        Customer customer = findByUuid(customerUuid);
        String taxNumber = validateTaxNumber(dto.taxNumber());

        if (dao.existsByTaxNumberInAnotherCustomer(taxNumber, customerUuid)) {
            throw new EntityExistsException("Já existe um cliente com esse CPF.");
        }

        customer.setTaxNumber(taxNumber);
        fillData(customer, dto);
        return dao.update(customer);
    }

    // Soft delete: o cliente continua no banco pra preservar o histórico de vendas.
    @Transactional
    public void deleteCustomer(UUID customerUuid) {
        Customer customer = findByUuid(customerUuid);
        customer.setDeletedAt(Instant.now());
        dao.update(customer);
    }

    private void fillData(Customer customer, CustomerRequest dto) {
        customer.setName(validateName(dto.name()));
        customer.setEmail(dto.email());
        customer.setPhone(dto.phone());
        customer.setAddress(buildAddress(dto));
    }

    private Address buildAddress(CustomerRequest dto) {
        // As colunas têm tamanho fixo: sem essa checagem o banco recusaria e a API daria 500.
        if (dto.state() != null && dto.state().length() != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UF deve ter 2 letras.");
        }
        if (dto.zipCode() != null && dto.zipCode().length() != 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CEP deve ter 8 dígitos, sem traço.");
        }

        Address address = new Address();
        address.setStreet(dto.street());
        address.setNumber(dto.number());
        address.setDistrict(dto.district());
        address.setCity(dto.city());
        address.setState(dto.state());
        address.setZipCode(dto.zipCode());
        return address;
    }

    // Aceita "123.456.789-00" ou "12345678900" e guarda só os números.
    private String validateTaxNumber(String taxNumber) {
        if (taxNumber == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CPF é obrigatório.");
        }

        String digits = taxNumber.replace(".", "").replace("-", "");
        if (digits.length() != 11) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CPF deve ter 11 dígitos.");
        }
        return digits;
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do cliente é obrigatório.");
        }
        return name.strip();
    }
}

