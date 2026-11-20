package br.edu.ucsal.bd2.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.dto.request.CustomerRequest;
import br.edu.ucsal.bd2.dto.response.CustomerResponse;
import br.edu.ucsal.bd2.dto.response.PageResponse;
import br.edu.ucsal.bd2.model.Customer;
import br.edu.ucsal.bd2.service.CustomerService;

@RestController
@RequestMapping("/api/customer")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    // GET /api/customer?page=0&size=20 (ordenado por nome; no máximo 100 por página)
    @GetMapping
    public ResponseEntity<PageResponse<CustomerResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            page = 0;
        }
        if (size < 1) {
            size = 1;
        }
        if (size > 100) {
            size = 100;
        }

        Page<Customer> customersPage = service.findAll(PageRequest.of(page, size));

        return ResponseEntity.ok(PageResponse.from(customersPage, CustomerResponse::convertToDTO));
    }

    @GetMapping("/{customerUuid}")
    public ResponseEntity<CustomerResponse> findByUuid(@PathVariable UUID customerUuid) {
        Customer customer = service.findByUuid(customerUuid);
        CustomerResponse response = CustomerResponse.convertToDTO(customer);

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(@RequestBody CustomerRequest request) {
        Customer customer = service.createCustomer(request);
        CustomerResponse response = CustomerResponse.convertToDTO(customer);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{customerUuid}")
    public ResponseEntity<CustomerResponse> updateCustomer(@PathVariable UUID customerUuid,
                                                           @RequestBody CustomerRequest request) {
        Customer customer = service.updateCustomer(customerUuid, request);
        CustomerResponse response = CustomerResponse.convertToDTO(customer);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{customerUuid}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable UUID customerUuid) {
        service.deleteCustomer(customerUuid);

        return ResponseEntity.noContent().build();
    }
}


