package br.edu.ucsal.bd2.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import br.edu.ucsal.bd2.dto.response.EtlRunResponse;
import br.edu.ucsal.bd2.dto.response.EtlRunResponse.TableCount;

// O ETL mora no banco (db/dw/02_etl.sql, procedure etl.executar). Aqui a aplicação só dispara a procedure e conta as
// linhas do DW antes e depois. As tabelas do DW não são entidades JPA, então o acesso é por SQL (JdbcTemplate).
//
// Sem @Transactional: o CALL é um comando só e já roda numa transação no banco (ou carrega tudo, ou nada). Se quem
// chama já estiver numa transação (como nos testes), o JdbcTemplate participa dela.
@Service
public class EtlService {

    // Ordem do relatório: o fato e as dimensões da estrela, depois as do floco. Nomes fixos, nunca vindos da requisição.
    public static final List<String> DW_TABLES = List.of(
            "dw_estrela.fato_venda",
            "dw_estrela.dim_tempo",
            "dw_estrela.dim_produto",
            "dw_estrela.dim_cliente",
            "dw_estrela.dim_loja",
            "dw_estrela.dim_funcionario",
            "dw_floco.fato_venda",
            "dw_floco.dim_tempo",
            "dw_floco.dim_produto",
            "dw_floco.dim_categoria",
            "dw_floco.dim_cliente",
            "dw_floco.dim_loja",
            "dw_floco.dim_cidade",
            "dw_floco.dim_estado",
            "dw_floco.dim_funcionario");

    private final JdbcTemplate jdbc;

    public EtlService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // completa = false: carga incremental (só o que ainda não está no DW). true: esvazia o DW e carrega tudo.
    public EtlRunResponse run(boolean completa) {
        List<Long> before = countRows();
        jdbc.update("CALL etl.executar(?)", completa);
        List<Long> after = countRows();

        Map<String, Object> log = jdbc.queryForMap("""
                SELECT id, tipo, itens_extraidos, fatos_inseridos, clientes_novas_versoes,
                       (extract(epoch FROM fim - inicio) * 1000)::bigint AS duracao_ms
                  FROM etl.execucao
                 ORDER BY id DESC
                 LIMIT 1
                """);

        List<TableCount> tables = new ArrayList<>();
        for (int i = 0; i < DW_TABLES.size(); i++) {
            tables.add(new TableCount(DW_TABLES.get(i), before.get(i), after.get(i)));
        }
        return new EtlRunResponse(
                ((Number) log.get("id")).longValue(),
                (String) log.get("tipo"),
                ((Number) log.get("duracao_ms")).longValue(),
                ((Number) log.get("itens_extraidos")).intValue(),
                ((Number) log.get("fatos_inseridos")).intValue(),
                ((Number) log.get("clientes_novas_versoes")).intValue(),
                tables);
    }

    private List<Long> countRows() {
        return DW_TABLES.stream()
                .map(table -> jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class))
                .toList();
    }
}
