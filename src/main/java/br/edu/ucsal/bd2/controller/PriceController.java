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

import br.edu.ucsal.bd2.dto.request.PriceRequest;
import br.edu.ucsal.bd2.dto.response.PageResponse;
import br.edu.ucsal.bd2.dto.response.PriceResponse;
import br.edu.ucsal.bd2.model.Price;
import br.edu.ucsal.bd2.service.PriceService;
import br.edu.ucsal.bd2.util.PageRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Preços", description = "Preço de cada produto, com histórico. Produto sem preço vigente não pode ser vendido. "
        + "O preço é identificado pelo produto (uuid na URL); preços antigos ficam no histórico.")
@RestController
@RequestMapping("/api/product/{productUuid}/price")
public class PriceController {

    private final PriceService service;

    public PriceController(PriceService service) {
        this.service = service;
    }

    @Operation(summary = "Busca o preço vigente do produto")
    @ApiResponse(responseCode = "200", description = "Preço vigente")
    @ApiResponse(responseCode = "404", description = "Produto inexistente ou sem preço vigente")
    @GetMapping
    public ResponseEntity<PriceResponse> findCurrent(@PathVariable UUID productUuid) {
        Price price = service.findCurrent(productUuid);

        return ResponseEntity.ok(PriceResponse.convertToDTO(price));
    }

    // GET /api/product/{uuid}/price/history?page=0&size=20 (do mais novo para o mais antigo; no máximo 100 por página)
    @Operation(summary = "Lista o histórico de preços do produto",
            description = "Do mais novo para o mais antigo, com o vigente e os substituídos. `validUntil` nulo é o preço vigente. "
                    + "`page` começa em 0; `size` vai de 1 a 100 (padrão 20).")
    @ApiResponse(responseCode = "200", description = "Página do histórico")
    @ApiResponse(responseCode = "404", description = "Produto inexistente")
    @GetMapping("/history")
    public ResponseEntity<PageResponse<PriceResponse>> findHistory(
            @PathVariable UUID productUuid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Price> history = service.findHistory(productUuid, PageRules.of(page, size));

        return ResponseEntity.ok(PageResponse.from(history, PriceResponse::convertToDTO));
    }

    @Operation(summary = "Define o primeiro preço do produto",
            description = "Coloca o produto à venda. Se ele já tem preço vigente, use o PUT.")
    @ApiResponse(responseCode = "201", description = "Preço definido")
    @ApiResponse(responseCode = "400", description = "Preço zero, negativo ou acima do limite")
    @ApiResponse(responseCode = "404", description = "Produto inexistente")
    @ApiResponse(responseCode = "409", description = "O produto já tem preço vigente")
    @PostMapping
    public ResponseEntity<PriceResponse> createPrice(@PathVariable UUID productUuid, @RequestBody PriceRequest request) {
        Price price = service.createPrice(productUuid, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(PriceResponse.convertToDTO(price));
    }

    @Operation(summary = "Altera o preço vigente do produto",
            description = "Grava o novo preço e fecha o antigo no histórico. Se o valor for igual ao vigente, nada muda.")
    @ApiResponse(responseCode = "200", description = "Preço vigente depois da alteração")
    @ApiResponse(responseCode = "400", description = "Preço zero, negativo ou acima do limite")
    @ApiResponse(responseCode = "404", description = "Produto inexistente ou sem preço vigente (use o POST)")
    @PutMapping
    public ResponseEntity<PriceResponse> updatePrice(@PathVariable UUID productUuid, @RequestBody PriceRequest request) {
        Price price = service.updatePrice(productUuid, request);

        return ResponseEntity.ok(PriceResponse.convertToDTO(price));
    }

    @Operation(summary = "Tira o produto de venda",
            description = "Fecha o preço vigente sem definir outro. O histórico é mantido; para voltar a vender, use o POST.")
    @ApiResponse(responseCode = "204", description = "Produto sem preço vigente")
    @ApiResponse(responseCode = "404", description = "Produto inexistente ou já sem preço vigente")
    @DeleteMapping
    public ResponseEntity<Void> deletePrice(@PathVariable UUID productUuid) {
        service.deletePrice(productUuid);

        return ResponseEntity.noContent().build();
    }
}
