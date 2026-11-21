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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.dto.request.StaffRequest;
import br.edu.ucsal.bd2.dto.response.PageResponse;
import br.edu.ucsal.bd2.dto.response.StaffResponse;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.service.StaffService;
import br.edu.ucsal.bd2.util.PageRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Funcionários", description = "Cadastro de funcionários (administradores, gerentes, caixas e estoquistas). Cada um "
        + "trabalha numa loja, menos o ADMIN, que é da rede. Exclusão lógica.")
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffService service;

    public StaffController(StaffService service) {
        this.service = service;
    }

    // GET /api/staff?page=0&size=20 (ordenado por nome; no máximo 100 por página). Com X-Store-Id, só os da loja.
    @Operation(summary = "Lista os funcionários ativos",
            description = "Paginado e ordenado por nome. Com o header X-Store-Id, só os funcionários da loja; sem ele, a rede "
                    + "inteira (inclusive o ADMIN). `page` começa em 0; `size` vai de 1 a 100 (padrão 20).")
    @ApiResponse(responseCode = "200", description = "Página de funcionários")
    @ApiResponse(responseCode = "404", description = "Loja do header inexistente ou apagada")
    @GetMapping
    public ResponseEntity<PageResponse<StaffResponse>> findAll(
            @Parameter(description = StoreHeader.OPTIONAL) @RequestHeader(value = StoreHeader.NAME, required = false) UUID storeUuid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Staff> staffPage = service.findAll(storeUuid, PageRules.of(page, size));

        return ResponseEntity.ok(PageResponse.from(staffPage, StaffResponse::convertToDTO));
    }

    @Operation(summary = "Busca um funcionário pelo uuid")
    @ApiResponse(responseCode = "200", description = "Funcionário encontrado")
    @ApiResponse(responseCode = "404", description = "Funcionário inexistente ou já apagado")
    @GetMapping("/{staffUuid}")
    public ResponseEntity<StaffResponse> findByUuid(@PathVariable UUID staffUuid) {
        Staff staff = service.findByUuid(staffUuid);

        return ResponseEntity.ok(StaffResponse.convertToDTO(staff));
    }

    @Operation(summary = "Cadastra um funcionário",
            description = "CPF, nome, perfil e loja são obrigatórios; o ADMIN é da rede e vai sem loja. O CPF é guardado só "
                    + "com os dígitos e não pode repetir (nem com o de um cliente).")
    @ApiResponse(responseCode = "201", description = "Funcionário criado")
    @ApiResponse(responseCode = "400", description = "CPF, nome, perfil, UF ou CEP inválidos; loja ausente, ou informada para o ADMIN")
    @ApiResponse(responseCode = "404", description = "Loja inexistente ou apagada")
    @ApiResponse(responseCode = "409", description = "CPF já cadastrado")
    @PostMapping
    public ResponseEntity<StaffResponse> createStaff(@RequestBody StaffRequest request) {
        Staff staff = service.createStaff(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(StaffResponse.convertToDTO(staff));
    }

    @Operation(summary = "Atualiza um funcionário",
            description = "Substitui todos os campos informados no cadastro. Trocar `storeUuid` transfere o funcionário de loja; "
                    + "o gerente de uma loja só pode ser transferido depois de trocado o gerente dela.")
    @ApiResponse(responseCode = "200", description = "Funcionário atualizado")
    @ApiResponse(responseCode = "400", description = "Dados inválidos")
    @ApiResponse(responseCode = "404", description = "Funcionário ou loja inexistente (ou apagado)")
    @ApiResponse(responseCode = "409", description = "CPF já cadastrado em outra pessoa, ou o funcionário gerencia a loja de onde sairia")
    @PutMapping("/{staffUuid}")
    public ResponseEntity<StaffResponse> updateStaff(@PathVariable UUID staffUuid, @RequestBody StaffRequest request) {
        Staff staff = service.updateStaff(staffUuid, request);

        return ResponseEntity.ok(StaffResponse.convertToDTO(staff));
    }

    @Operation(summary = "Apaga um funcionário (exclusão lógica)",
            description = "O registro fica no banco para preservar o histórico de vendas. Quem gerencia uma loja não pode ser apagado.")
    @ApiResponse(responseCode = "204", description = "Funcionário apagado")
    @ApiResponse(responseCode = "404", description = "Funcionário inexistente ou já apagado")
    @ApiResponse(responseCode = "409", description = "Funcionário é gerente de uma loja")
    @DeleteMapping("/{staffUuid}")
    public ResponseEntity<Void> deleteStaff(@PathVariable UUID staffUuid) {
        service.deleteStaff(staffUuid);

        return ResponseEntity.noContent().build();
    }
}
