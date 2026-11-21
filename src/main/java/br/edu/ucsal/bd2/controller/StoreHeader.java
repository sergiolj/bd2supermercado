package br.edu.ucsal.bd2.controller;

// Multi-loja: uma operação de loja (compra, estoque, venda) diz de qual loja é pelo header X-Store-Id, com o uuid
// da loja. Onde ele é obrigatório, sem ele a resposta é 400; com uma loja inexistente ou apagada, 404.
public final class StoreHeader {

    public static final String NAME = "X-Store-Id";

    public static final String REQUIRED = "uuid da loja da operação. Loja inexistente ou apagada responde 404.";

    public static final String OPTIONAL = "uuid de uma loja, para ver só os dados dela. Sem o header, vale a rede inteira.";

    private StoreHeader() {
    }
}
