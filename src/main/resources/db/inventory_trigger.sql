-- Automacao do estoque: inventory_movement -> inventory.
--
-- Executado a cada subida da aplicacao (spring.sql.init.*), depois que o Hibernate
-- criou/atualizou as tabelas. Tudo aqui e idempotente.
-- Os comandos sao separados por duas barras de ponto e virgula (spring.sql.init.separator),
-- porque o corpo das funcoes PL/pgSQL contem ponto e virgula.
--
-- Regras:
--   INSERT em inventory_movement: PURCHASE soma; SALE e LOSS subtraem do inventory.
--   DELETE em inventory_movement: desfaz o efeito da movimentacao removida.
--   UPDATE em inventory_movement: proibido (a movimentacao e um registro imutavel).
--   O estoque nunca pode ficar negativo; se ficaria, a operacao inteira e desfeita.

-- Guardas de integridade -----------------------------------------------------------------

ALTER TABLE inventory DROP CONSTRAINT IF EXISTS ck_inventory_total_quantity_non_negative;;
ALTER TABLE inventory ADD CONSTRAINT ck_inventory_total_quantity_non_negative
    CHECK (total_quantity >= 0);;

-- Quantidade sempre positiva: o sentido (entrada/saida) vem do tipo da movimentacao.
ALTER TABLE inventory_movement DROP CONSTRAINT IF EXISTS ck_inventory_movement_quantity_positive;;
ALTER TABLE inventory_movement ADD CONSTRAINT ck_inventory_movement_quantity_positive
    CHECK (quantity > 0);;

-- INSERT / DELETE: aplica ou desfaz a movimentacao no inventory ------------------------------

CREATE OR REPLACE FUNCTION fn_inventory_movement_apply() RETURNS trigger AS $$
DECLARE
    mov   inventory_movement;
    delta numeric(12,3);
BEGIN
    IF TG_OP = 'INSERT' THEN
        mov := NEW;
    ELSE
        mov := OLD;
    END IF;

    delta := CASE mov.type
        WHEN 'PURCHASE' THEN  mov.quantity
        WHEN 'SALE'     THEN -mov.quantity
        WHEN 'LOSS'     THEN -mov.quantity
        ELSE NULL
    END;

    IF delta IS NULL THEN
        RAISE EXCEPTION 'Tipo de movimentacao de estoque invalido: %', mov.type;
    END IF;

    -- Remover uma movimentacao e aplicar o efeito contrario.
    IF TG_OP = 'DELETE' THEN
        delta := -delta;
    END IF;

    BEGIN
        -- Garante a linha do produto (seguro contra concorrencia). Nao da para fazer o upsert
        -- direto com o delta: o CHECK valida a linha proposta antes de resolver o conflito,
        -- entao um delta negativo seria rejeitado mesmo havendo saldo.
        INSERT INTO inventory (product_id, total_quantity, updated_at)
        VALUES (mov.product_id, 0, now())
        ON CONFLICT (product_id) DO NOTHING;

        -- O UPDATE trava a linha e o CHECK de saldo >= 0 valida o resultado.
        UPDATE inventory
           SET total_quantity = total_quantity + delta,
               updated_at     = now()
         WHERE product_id = mov.product_id;
    EXCEPTION WHEN check_violation THEN
        RAISE EXCEPTION 'Estoque insuficiente: a operacao (% de % unidades) deixaria o estoque do produto % negativo',
            TG_OP, mov.quantity, mov.product_id
            USING ERRCODE = 'check_violation';
    END;

    RETURN NULL; -- valor ignorado em trigger AFTER
END;
$$ LANGUAGE plpgsql;;

DROP TRIGGER IF EXISTS trg_inventory_movement_after_insert ON inventory_movement;;
CREATE TRIGGER trg_inventory_movement_after_insert
    AFTER INSERT ON inventory_movement
    FOR EACH ROW EXECUTE FUNCTION fn_inventory_movement_apply();;

DROP TRIGGER IF EXISTS trg_inventory_movement_after_delete ON inventory_movement;;
CREATE TRIGGER trg_inventory_movement_after_delete
    AFTER DELETE ON inventory_movement
    FOR EACH ROW EXECUTE FUNCTION fn_inventory_movement_apply();;

-- UPDATE: bloqueado --------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_inventory_movement_block_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'inventory_movement e imutavel: UPDATE nao permitido (exclua a movimentacao e registre outra)'
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;;

DROP TRIGGER IF EXISTS trg_inventory_movement_before_update ON inventory_movement;;
CREATE TRIGGER trg_inventory_movement_before_update
    BEFORE UPDATE ON inventory_movement
    FOR EACH ROW EXECUTE FUNCTION fn_inventory_movement_block_update();;
