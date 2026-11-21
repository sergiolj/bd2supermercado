package br.edu.ucsal.bd2.dto.request;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

// Corpo do POST/PUT de loja. Só o nome é obrigatório; o gerente e o endereço são opcionais.
@Schema(description = "Dados da loja")
public record StoreRequest(
        @Schema(description = "Nome da loja, único no sistema", example = "Mercado Central - Pituba",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "uuid do funcionário gerente. Precisa ser um funcionário ativo que não gerencie outra loja. "
                + "Omitido ou nulo = loja sem gerente.", example = "804fb0bc-10a5-e18c-93b1-9e6e98d49440")
        UUID managerUuid,
        @Schema(description = "Rua", example = "Av. Manoel Dias da Silva")
        String street,
        @Schema(description = "Número", example = "1200")
        String number,
        @Schema(description = "Bairro", example = "Pituba")
        String district,
        @Schema(description = "Cidade", example = "Salvador")
        String city,
        @Schema(description = "UF, 2 letras", example = "BA")
        String state,
        @Schema(description = "CEP, com ou sem traço (a API guarda só os 8 dígitos)", example = "41830-000")
        String zipCode
) {
}
