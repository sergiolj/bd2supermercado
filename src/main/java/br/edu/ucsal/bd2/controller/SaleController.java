package br.edu.ucsal.bd2.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.dto.request.PaymentRequest;
import br.edu.ucsal.bd2.dto.request.SaleRequest;
import br.edu.ucsal.bd2.dto.response.SaleResponse;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.service.SaleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Vendas", description = "Vendas de uma loja, informada no header X-Store-Id. A venda é aberta com os itens (OPEN), "
        + "recebe um ou mais pagamentos e é concluída (COMPLETED) quando paga integralmente: nesse momento cada item vira uma "
        + "baixa no estoque da loja, na mesma transação do pagamento. A busca com filtros fica em /api/criteria/sales.")
@RestController
@RequestMapping("/api/sale")
public class SaleController {

    private final SaleService service;

    public SaleController(SaleService service) {
        this.service = service;
    }

    @Operation(summary = "Busca uma venda pelo uuid, com itens e pagamentos")
    @ApiResponse(responseCode = "200", description = "Venda encontrada")
    @ApiResponse(responseCode = "400", description = "Header X-Store-Id ausente ou inválido")
    @ApiResponse(responseCode = "404", description = "Loja ou venda inexistente (ou venda de outra loja)")
    @GetMapping("/{saleUuid}")
    public ResponseEntity<SaleResponse> findByUuid(
            @Parameter(description = StoreHeader.REQUIRED) @RequestHeader(StoreHeader.NAME) UUID storeUuid,
            @PathVariable UUID saleUuid) {
        Sale sale = service.findByUuid(storeUuid, saleUuid);

        return ResponseEntity.ok(SaleResponse.convertToDTO(sale));
    }

    @Operation(summary = "Abre uma venda com os seus itens",
            description = "A venda nasce em aberto (OPEN), à espera do pagamento. Cada item leva o preço vigente do produto "
                    + "naquele momento e precisa ter saldo no estoque da loja, mas o estoque só baixa quando a venda é paga.")
    @ApiResponse(responseCode = "201", description = "Venda aberta")
    @ApiResponse(responseCode = "400", description = "Header ausente, sem itens, produto repetido, quantidade ou CPF/CNPJ inválidos")
    @ApiResponse(responseCode = "404", description = "Loja, vendedor ou produto inexistente (ou apagado)")
    @ApiResponse(responseCode = "409", description = "Vendedor de outra loja, produto sem preço vigente ou estoque insuficiente")
    @PostMapping
    public ResponseEntity<SaleResponse> openSale(
            @Parameter(description = StoreHeader.REQUIRED) @RequestHeader(StoreHeader.NAME) UUID storeUuid,
            @RequestBody SaleRequest request) {
        Sale sale = service.openSale(storeUuid, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(SaleResponse.convertToDTO(sale));
    }

    @Operation(summary = "Registra um pagamento da venda",
            description = "O valor não pode passar do que falta pagar; a venda pode ser paga em partes. O pagamento que quita o "
                    + "total conclui a venda (COMPLETED) e baixa o estoque de cada item, tudo na mesma transação: se algum item "
                    + "não tiver mais saldo, nada é gravado e a venda continua em aberto.")
    @ApiResponse(responseCode = "200", description = "Pagamento registrado (a resposta mostra se a venda foi concluída)")
    @ApiResponse(responseCode = "400", description = "Header ausente, forma de pagamento ausente, ou valor zero ou acima do que falta")
    @ApiResponse(responseCode = "404", description = "Loja ou venda inexistente (ou venda de outra loja)")
    @ApiResponse(responseCode = "409", description = "Venda já concluída ou cancelada, ou estoque insuficiente para efetivá-la")
    @PostMapping("/{saleUuid}/payment")
    public ResponseEntity<SaleResponse> registerPayment(
            @Parameter(description = StoreHeader.REQUIRED) @RequestHeader(StoreHeader.NAME) UUID storeUuid,
            @PathVariable UUID saleUuid,
            @RequestBody PaymentRequest request) {
        Sale sale = service.registerPayment(storeUuid, saleUuid, request);

        return ResponseEntity.ok(SaleResponse.convertToDTO(sale));
    }

    @Operation(summary = "Cancela uma venda em aberto",
            description = "Os pagamentos já recebidos são estornados (REFUNDED). O estoque não muda, porque só sai na conclusão. "
                    + "Venda concluída não pode ser cancelada.")
    @ApiResponse(responseCode = "200", description = "Venda cancelada")
    @ApiResponse(responseCode = "404", description = "Loja ou venda inexistente (ou venda de outra loja)")
    @ApiResponse(responseCode = "409", description = "Venda já concluída ou cancelada")
    @PostMapping("/{saleUuid}/cancel")
    public ResponseEntity<SaleResponse> cancelSale(
            @Parameter(description = StoreHeader.REQUIRED) @RequestHeader(StoreHeader.NAME) UUID storeUuid,
            @PathVariable UUID saleUuid) {
        Sale sale = service.cancelSale(storeUuid, saleUuid);

        return ResponseEntity.ok(SaleResponse.convertToDTO(sale));
    }
}
