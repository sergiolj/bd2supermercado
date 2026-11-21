package br.edu.ucsal.bd2.dto.request;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

// Corpo do POST/PUT de loja. Só o nome é obrigatório; o gerente e o endereço são opcionais.
// O gerente precisa ser funcionário da própria loja, então só pode ser definido no PUT (a loja nova não tem funcionários).
@Schema(description = "Dados da loja")
public record StoreRequest(
        @Schema(description = "Nome da loja, único no sistema", example = "Mercado Central - Pituba",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "uuid do funcionário gerente. Precisa ser um funcionário ativo da própria loja, então fica vazio "
                + "na criação e é definido depois, no PUT. Omitido ou nulo = loja sem gerente.",
                example = "131f7c2b-d57b-5d64-96f4-122592720b65")
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
