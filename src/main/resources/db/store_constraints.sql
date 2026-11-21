-- Regras de loja (multi-loja) que as anotacoes do JPA nao conseguem expressar.
--
-- Executado a cada subida da aplicacao (spring.sql.init.*), depois que o Hibernate criou/atualizou as tabelas
-- e antes do inventory_trigger.sql. Tudo aqui e idempotente. Os comandos sao separados por duas barras de ponto
-- e virgula (spring.sql.init.separator), porque o corpo das funcoes PL/pgSQL contem ponto e virgula.
--
-- Onde a loja aparece:
--   staff, sale, inventory e inventory_movement tem store_id. payment e sale_items chegam a loja pela venda;
--   product, category, supplier, customer e price sao da rede (compartilhados por todas as lojas).
--
-- Regras garantidas aqui:
--   * Todo funcionario trabalha numa loja, menos o ADMIN, que e da rede e nao tem loja (CHECK).
--   * O gerente de uma loja e funcionario dela (FK composta store -> staff).
--   * Uma movimentacao de venda mexe no estoque da loja da propria venda (FK composta inventory_movement -> sale).
--   * O vendedor trabalha na loja da venda quando ela e registrada (trigger). Aqui nao da para usar FK composta:
--     ela impediria transferir o funcionario, e as vendas antigas precisam continuar na loja onde aconteceram.

-- Banco criado antes de a loja entrar no modelo -------------------------------------------------------
-- O ddl-auto=update nao consegue criar uma coluna NOT NULL numa tabela que ja tem linhas: a coluna fica
-- faltando e a aplicacao so quebraria mais tarde, longe da causa. Aqui ela para na subida, ja com a instrucao.

DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(t.name || '.store_id', ', ') INTO missing
      FROM unnest(ARRAY['staff', 'sale', 'inventory', 'inventory_movement']) AS t(name)
     WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns c
                        WHERE c.table_schema = current_schema()
                          AND c.table_name = t.name
                          AND c.column_name = 'store_id');

    IF missing IS NULL AND EXISTS (SELECT 1 FROM staff WHERE store_id IS NULL AND role <> 'ADMIN') THEN
        missing := 'loja dos funcionarios que ja existiam';
    END IF;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'O banco foi criado antes de a loja entrar no modelo (falta: %). Apague os dados e rode o seed de novo: docker compose down -v, docker compose up -d e ./mvnw -Pseed (ou o TRUNCATE do README.TXT).',
            missing;
    END IF;
END
$$;;

-- Funcionario sem loja so se for ADMIN, e o ADMIN nunca tem loja ---------------------------------------

ALTER TABLE staff DROP CONSTRAINT IF EXISTS ck_staff_store_by_role;;
ALTER TABLE staff ADD CONSTRAINT ck_staff_store_by_role
    CHECK ((role = 'ADMIN') = (store_id IS NULL));;

-- Chaves compostas ------------------------------------------------------------------------------------
-- Uma FK so pode apontar para colunas com UNIQUE. Como id ja e unico, (id, store_id) tambem e: as duas
-- constraints uk_* existem so para servir de alvo das FKs compostas. As FKs saem antes porque dependem delas.

ALTER TABLE store DROP CONSTRAINT IF EXISTS fk_store_manager_same_store;;
ALTER TABLE inventory_movement DROP CONSTRAINT IF EXISTS fk_inventory_movement_sale_same_store;;

ALTER TABLE staff DROP CONSTRAINT IF EXISTS uk_staff_id_store;;
ALTER TABLE staff ADD CONSTRAINT uk_staff_id_store UNIQUE (id, store_id);;

ALTER TABLE sale DROP CONSTRAINT IF EXISTS uk_sale_id_store;;
ALTER TABLE sale ADD CONSTRAINT uk_sale_id_store UNIQUE (id, store_id);;

-- O gerente trabalha na loja que gerencia. Loja sem gerente (manager_id nulo) nao e verificada (MATCH SIMPLE).
-- Transferir o gerente para outra loja tambem e recusado: a loja deixaria de achar (manager_id, id) em staff.
ALTER TABLE store ADD CONSTRAINT fk_store_manager_same_store
    FOREIGN KEY (manager_id, id) REFERENCES staff (id, store_id);;

-- Movimentacao de venda: a loja e a da venda. Compra e perda tem sale_id nulo e ficam de fora (MATCH SIMPLE).
ALTER TABLE inventory_movement ADD CONSTRAINT fk_inventory_movement_sale_same_store
    FOREIGN KEY (sale_id, store_id) REFERENCES sale (id, store_id);;

-- Vendedor da loja da venda ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_sale_seller_same_store() RETURNS trigger AS $$
BEGIN
    -- O Hibernate regrava todas as colunas no UPDATE. So confere quando o vendedor ou a loja mudam de fato:
    -- senao a venda antiga de um funcionario ja transferido nao poderia mais ser atualizada (ex.: o status).
    IF TG_OP = 'UPDATE' AND NEW.seller_id = OLD.seller_id AND NEW.store_id = OLD.store_id THEN
        RETURN NEW;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM staff WHERE id = NEW.seller_id AND store_id = NEW.store_id) THEN
        RAISE EXCEPTION 'O vendedor % nao trabalha na loja % da venda', NEW.seller_id, NEW.store_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;;

DROP TRIGGER IF EXISTS trg_sale_seller_same_store ON sale;;
CREATE TRIGGER trg_sale_seller_same_store
    BEFORE INSERT OR UPDATE OF seller_id, store_id ON sale
    FOR EACH ROW EXECUTE FUNCTION fn_sale_seller_same_store();;
