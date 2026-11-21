package br.edu.ucsal.bd2.dto.request;

import java.util.UUID;

import br.edu.ucsal.bd2.util.StaffRole;
import io.swagger.v3.oas.annotations.media.Schema;

// Corpo do POST/PUT de funcionário. CPF, nome e perfil são obrigatórios, e a loja também (menos para o ADMIN).
// O endereço inteiro é opcional. Trocar a loja no PUT é transferir o funcionário.
@Schema(description = "Dados do funcionário")
public record StaffRequest(
        @Schema(description = "CPF, com ou sem pontuação (a API guarda só os 11 dígitos). Único no sistema, inclusive entre clientes.",
                example = "123.456.789-09", requiredMode = Schema.RequiredMode.REQUIRED)
        String taxNumber,
        @Schema(description = "Nome completo", example = "Juliana Souza Ribeiro", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "Perfil de acesso: ADMIN, MANAGER, CASHIER (caixa) ou STOCKIST (estoquista)",
                example = "CASHIER", requiredMode = Schema.RequiredMode.REQUIRED)
        StaffRole role,
        @Schema(description = "uuid da loja onde trabalha (ativa). Obrigatório, menos para o ADMIN, que é da rede e fica sem loja. "
                + "Quem gerencia uma loja não pode ser transferido para outra.", example = "3dfd2951-8c4c-16bc-eeed-993186f0f26e")
        UUID storeUuid,
        @Schema(description = "E-mail", example = "juliana@mercado.com.br")
        String email,
        @Schema(description = "Telefone", example = "(71) 99999-0000")
        String phone,
        @Schema(description = "Rua", example = "Rua das Flores")
        String street,
        @Schema(description = "Número", example = "10")
        String number,
        @Schema(description = "Bairro", example = "Pituba")
        String district,
        @Schema(description = "Cidade", example = "Salvador")
        String city,
        @Schema(description = "UF, 2 letras", example = "BA")
        String state,
        @Schema(description = "CEP, com ou sem traço (a API guarda só os 8 dígitos)", example = "41810-000")
        String zipCode
) {
}
