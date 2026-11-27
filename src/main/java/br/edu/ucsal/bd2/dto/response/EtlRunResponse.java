package br.edu.ucsal.bd2.dto.response;

import java.util.List;

// Resultado de uma execução do ETL: o que a procedure registrou em etl.execucao e as contagens de linhas de cada
// tabela do DW antes e depois da carga.
public record EtlRunResponse(
        long execucao,
        String tipo,
        long duracaoMs,
        int itensExtraidos,
        int fatosInseridos,
        int clientesNovasVersoes,
        List<TableCount> tabelas
) {

    public record TableCount(
            String tabela,
            long antes,
            long depois
    ) {
    }
}
