package br.edu.ucsal.bd2.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
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
import br.edu.ucsal.bd2.util.PageRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Clientes", description = "Cadastro de clientes, identificados pelo CPF. "
        + "Exclusão lógica: o histórico de vendas é preservado.")
@RestController
@RequestMapping("/api/customer")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    // GET /api/customer?page=0&size=20 (ordenado por nome; no máximo 100 por página)
    @Operation(summary = "Lista os clientes ativos",
            description = "Paginado e ordenado por nome. `page` começa em 0; `size` vai de 1 a 100 (padrão 20).")
    @ApiResponse(responseCode = "200", description = "Página de clientes")
    @GetMapping
    public ResponseEntity<PageResponse<CustomerResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Customer> customersPage = service.findAll(PageRules.of(page, size));

        return ResponseEntity.ok(PageResponse.from(customersPage, CustomerResponse::convertToDTO));
    }

    @Operation(summary = "Busca um cliente pelo uuid")
    @ApiResponse(responseCode = "200", description = "Cliente encontrado")
    @ApiResponse(responseCode = "404", description = "Cliente inexistente ou já apagado")
    @GetMapping("/{customerUuid}")
    public ResponseEntity<CustomerResponse> findByUuid(@PathVariable UUID customerUuid) {
        Customer customer = service.findByUuid(customerUuid);
        CustomerResponse response = CustomerResponse.convertToDTO(customer);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Cadastra um cliente",
            description = "CPF e nome são obrigatórios. O CPF pode vir com ou sem pontuação (é guardado só com os dígitos) "
                    + "e não pode repetir (nem com o de um funcionário).")
    @ApiResponse(responseCode = "201", description = "Cliente criado")
    @ApiResponse(responseCode = "400", description = "CPF, nome, UF ou CEP inválidos")
    @ApiResponse(responseCode = "409", description = "CPF já cadastrado")
    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(@RequestBody CustomerRequest request) {
        Customer customer = service.createCustomer(request);
        CustomerResponse response = CustomerResponse.convertToDTO(customer);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Atualiza um cliente", description = "Substitui todos os campos informados no cadastro.")
    @ApiResponse(responseCode = "200", description = "Cliente atualizado")
    @ApiResponse(responseCode = "400", description = "CPF, nome, UF ou CEP inválidos")
    @ApiResponse(responseCode = "404", description = "Cliente inexistente ou já apagado")
    @ApiResponse(responseCode = "409", description = "CPF já usado por outra pessoa")
    @PutMapping("/{customerUuid}")
    public ResponseEntity<CustomerResponse> updateCustomer(@PathVariable UUID customerUuid,
                                                           @RequestBody CustomerRequest request) {
        Customer customer = service.updateCustomer(customerUuid, request);
        CustomerResponse response = CustomerResponse.convertToDTO(customer);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Apaga um cliente (exclusão lógica)",
            description = "O cliente continua no banco para preservar o histórico de vendas.")
    @ApiResponse(responseCode = "204", description = "Cliente apagado")
    @ApiResponse(responseCode = "404", description = "Cliente inexistente ou já apagado")
    @DeleteMapping("/{customerUuid}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable UUID customerUuid) {
        service.deleteCustomer(customerUuid);

        return ResponseEntity.noContent().build();
    }
}


