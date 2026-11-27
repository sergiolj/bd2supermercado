package br.edu.ucsal.bd2.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.dto.response.EtlRunResponse;
import br.edu.ucsal.bd2.service.EtlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/etl")
@Tag(name = "Data Warehouse (ETL)", description = "Carrega o Data Warehouse (modelos estrela e floco de neve) a partir "
        + "das vendas concluídas. O ETL roda no banco, nas procedures de db/dw/02_etl.sql.")
public class EtlController {

    private final EtlService service;

    public EtlController(EtlService service) {
        this.service = service;
    }

    @Operation(summary = "Executa o ETL e devolve as contagens de linhas do DW antes e depois",
            description = "Incremental por padrão: só entram os itens de vendas concluídas que ainda não estão no DW, e "
                    + "uma mudança de cidade do cliente gera uma nova versão dele (SCD tipo 2). Com `completa=true`, "
                    + "o DW é esvaziado e carregado de novo.")
    @ApiResponse(responseCode = "200", description = "Carga concluída")
    @PostMapping("/run")
    public ResponseEntity<EtlRunResponse> run(
            @Parameter(description = "true esvazia o DW e carrega tudo de novo")
            @RequestParam(defaultValue = "false") boolean completa) {
        return ResponseEntity.ok(service.run(completa));
    }
}
