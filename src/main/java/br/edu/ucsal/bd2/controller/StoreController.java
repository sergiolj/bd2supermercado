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

import br.edu.ucsal.bd2.dto.request.StoreRequest;
import br.edu.ucsal.bd2.dto.response.PageResponse;
import br.edu.ucsal.bd2.dto.response.StoreResponse;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.service.StoreService;
import br.edu.ucsal.bd2.util.PageRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Lojas", description = "Cadastro de lojas e de seus gerentes (funcionários da própria loja). O uuid da loja é o "
        + "que as operações de loja (compras, estoque, vendas) mandam no header X-Store-Id. Exclusão lógica.")
@RestController
@RequestMapping("/api/store")
public class StoreController {

    private final StoreService service;

    public StoreController(StoreService service) {
        this.service = service;
    }

    // GET /api/store?page=0&size=20 (ordenado por nome; no máximo 100 por página)
    @Operation(summary = "Lista as lojas ativas",
            description = "Paginado e ordenado por nome, já com o nome do gerente. `page` começa em 0; `size` vai de 1 a 100 (padrão 20).")
    @ApiResponse(responseCode = "200", description = "Página de lojas")
    @GetMapping
    public ResponseEntity<PageResponse<StoreResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Store> storesPage = service.findAll(PageRules.of(page, size));

        return ResponseEntity.ok(PageResponse.from(storesPage, StoreResponse::convertToDTO));
    }

    @Operation(summary = "Busca uma loja pelo uuid")
    @ApiResponse(responseCode = "200", description = "Loja encontrada")
    @ApiResponse(responseCode = "404", description = "Loja inexistente ou já apagada")
    @GetMapping("/{storeUuid}")
    public ResponseEntity<StoreResponse> findByUuid(@PathVariable UUID storeUuid) {
        Store store = service.findByUuid(storeUuid);

        return ResponseEntity.ok(StoreResponse.convertToDTO(store));
    }

    @Operation(summary = "Cadastra uma loja",
            description = "Só o nome é obrigatório. A loja nasce sem gerente: o gerente precisa ser funcionário dela, então "
                    + "cadastre o funcionário com essa loja e depois defina o gerente no PUT.")
    @ApiResponse(responseCode = "201", description = "Loja criada")
    @ApiResponse(responseCode = "400", description = "Nome, UF ou CEP inválidos, ou `managerUuid` informado")
    @ApiResponse(responseCode = "409", description = "Nome já usado por outra loja")
    @PostMapping
    public ResponseEntity<StoreResponse> createStore(@RequestBody StoreRequest request) {
        Store store = service.createStore(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(StoreResponse.convertToDTO(store));
    }

    @Operation(summary = "Atualiza uma loja",
            description = "Substitui todos os campos. Sem `managerUuid`, a loja fica sem gerente. O gerente precisa ser um "
                    + "funcionário ativo da própria loja.")
    @ApiResponse(responseCode = "200", description = "Loja atualizada")
    @ApiResponse(responseCode = "400", description = "Dados inválidos")
    @ApiResponse(responseCode = "404", description = "Loja ou gerente inexistente")
    @ApiResponse(responseCode = "409", description = "Nome já usado por outra loja, ou o gerente não trabalha nessa loja")
    @PutMapping("/{storeUuid}")
    public ResponseEntity<StoreResponse> updateStore(@PathVariable UUID storeUuid, @RequestBody StoreRequest request) {
        Store store = service.updateStore(storeUuid, request);

        return ResponseEntity.ok(StoreResponse.convertToDTO(store));
    }

    @Operation(summary = "Apaga uma loja (exclusão lógica)",
            description = "O histórico (vendas, compras, estoque) fica no banco. Só pode ser apagada sem funcionários ativos: "
                    + "transfira-os ou apague-os antes (o gerente também é funcionário da loja).")
    @ApiResponse(responseCode = "204", description = "Loja apagada")
    @ApiResponse(responseCode = "404", description = "Loja inexistente ou já apagada")
    @ApiResponse(responseCode = "409", description = "A loja ainda tem funcionários ativos")
    @DeleteMapping("/{storeUuid}")
    public ResponseEntity<Void> deleteStore(@PathVariable UUID storeUuid) {
        service.deleteStore(storeUuid);

        return ResponseEntity.noContent().build();
    }
}
