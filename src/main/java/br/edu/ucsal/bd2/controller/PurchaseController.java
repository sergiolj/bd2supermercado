package br.edu.ucsal.bd2.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.dto.request.PurchaseRequest;
import br.edu.ucsal.bd2.dto.response.PageResponse;
import br.edu.ucsal.bd2.dto.response.PurchaseResponse;
import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.service.PurchaseService;
import br.edu.ucsal.bd2.util.PageRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Compras", description = "Lançamentos de compra de reposição de estoque (movimentação PURCHASE). "
        + "O estoque é atualizado automaticamente pelo banco. O lançamento não pode ser editado: para corrigir, apague e lance outro.")
@RestController
@RequestMapping("/api/purchase")
public class PurchaseController {

    private final PurchaseService service;

    public PurchaseController(PurchaseService service) {
        this.service = service;
    }

    // GET /api/purchase?page=0&size=20 (mais recentes primeiro; no máximo 100 por página)
    @Operation(summary = "Lista os lançamentos de compra",
            description = "Os mais recentes primeiro. `page` começa em 0; `size` vai de 1 a 100 (padrão 20).")
    @ApiResponse(responseCode = "200", description = "Página de compras")
    @GetMapping
    public ResponseEntity<PageResponse<PurchaseResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<InventoryMovement> purchasesPage = service.findAll(PageRules.of(page, size));

        return ResponseEntity.ok(PageResponse.from(purchasesPage, PurchaseResponse::convertToDTO));
    }

    @Operation(summary = "Busca um lançamento de compra pelo uuid")
    @ApiResponse(responseCode = "200", description = "Compra encontrada")
    @ApiResponse(responseCode = "404", description = "Compra inexistente (ou o uuid é de uma venda/perda)")
    @GetMapping("/{purchaseUuid}")
    public ResponseEntity<PurchaseResponse> findByUuid(@PathVariable UUID purchaseUuid) {
        InventoryMovement purchase = service.findByUuid(purchaseUuid);

        return ResponseEntity.ok(PurchaseResponse.convertToDTO(purchase));
    }

    @Operation(summary = "Lança uma compra",
            description = "Soma a quantidade ao estoque do produto (o banco faz isso automaticamente). Produto e fornecedor "
                    + "precisam estar ativos. O custo é por unidade de medida do produto, em centavos.")
    @ApiResponse(responseCode = "201", description = "Compra lançada")
    @ApiResponse(responseCode = "400", description = "Quantidade, custo ou validade inválidos (ex.: fração em produto por unidade)")
    @ApiResponse(responseCode = "404", description = "Produto ou fornecedor inexistente (ou apagado)")
    @PostMapping
    public ResponseEntity<PurchaseResponse> registerPurchase(@RequestBody PurchaseRequest request) {
        InventoryMovement purchase = service.registerPurchase(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(PurchaseResponse.convertToDTO(purchase));
    }

    @Operation(summary = "Apaga um lançamento de compra (estorno)",
            description = "Exclusão definitiva, para lançamentos feitos por engano. A quantidade sai do estoque; se parte dela "
                    + "já foi vendida ou perdida, o estoque ficaria negativo e a exclusão é recusada.")
    @ApiResponse(responseCode = "204", description = "Compra apagada e estoque ajustado")
    @ApiResponse(responseCode = "404", description = "Compra inexistente")
    @ApiResponse(responseCode = "409", description = "Parte da quantidade já saiu do estoque")
    @DeleteMapping("/{purchaseUuid}")
    public ResponseEntity<Void> deletePurchase(@PathVariable UUID purchaseUuid) {
        service.deletePurchase(purchaseUuid);

        return ResponseEntity.noContent().build();
    }
}
