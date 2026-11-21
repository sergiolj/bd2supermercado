O que você vai encontrar nesse documento:
1. Convenção para commits
2. Metodologia para edições
3. Ferramentas de testes

1. Convenção de COMMITS
A estrutura dos commits deve ser dividida em três partes(Cabeçalho, Corpo e Rodapé), sendo o cabeçalho a única parte
obrigatória.

Não use pontos finais de frase
Não use maiúsculas no início de frase
Não use espaços entre o tipo e o escopo refactor(função)
Use linhas em branco entre as partes

<tipo>(<escopo opcional>): <descrição no imperativo>
<LINHA EM BRANCO>
[corpo opcional contendo o contexto detalhado]
<LINHA EM BRANCO>
[rodapé opcional contendo referências de issues ou quebras de compatibilidade]

CONSULTAR CATÁLOGO DE TIPOS (TAXONOMIA)
PREFIXO

refactor: Uma alteração estrutural de código que não adiciona novas regras de negócio nem corrige bugs
(a definição exata de Fowler)
test: Adição, refatoração ou correção de oráculos de teste (Jest ou JUnit)
chore: Alterações em infraestrutura, ferramentas de build ou arquivos de manifesto
(como atualizar o package.json ou o .gitignore)

Usar a taxonomia do Conventional Commits no título, e o catálogo de Padrões de
Refatoração de Martin Fowler no corpo da mensagem.

Use textos no modo Imperativo Obrigatório: Nunca escreva no passado ("extracted", "renamed").
A regra técnica para validação é que o título do commit deve completar perfeitamente a frase:
"Se aplicado, este commit irá..." (Exemplo: "Se aplicado, este commit irá... extract amount calculation").

Use nos commits o mesmo idioma do código e variáveis.

EXEMPLOS:
-> Extract Function (Extração da lógica de cálculo)

refactor(statement): extract amount calculation for a performance

- Technique: Extract Function
- Isolates the switch-case block that calculates the value of a single play.
- Removes the temporary accumulation logic from the main loop to prepare for polymorphism.

-> Clean Code / Rename Variable

refactor(statement): rename 'perf' variable to 'aPerformance'

- Technique: Rename Variable
- Improves lexical scope clarity and follows the book's naming convention for loop variables.

-> Adição de arquivos e documentos complementares
docs(convention): add information about how to use rules to create a commit
docs(taxonomia): add conventional commits reference file

2. METODOLOGIA PARA EDIÇÃO

NUNCA FAÇA COMMITS DIRETAMENTE NA BRANCH MAIN!!

2.1 Clonagem do repositório
git clone <url-do-repositorio-aqui>
cd <nome-da-pasta-do-projeto>

2.2 Mantenha a versão mais recente na sua máquina local
git checkout main
git pull origin main

2.3 Crie uma branch com a tarefa que irá executar usando a taxonomia de commits
# Exemplo para refatoração
git checkout -b refactor/extract-amount-calc

# Exemplo para configuração de infraestrutura
git checkout -b chore/update-gitignore

#Exemplo para novas features
git checkout -b feat/shoppingChart

2.4 Crie commits com alterações pequenas e que funcionem de maneira independente
git add <arquivos-modificados>
git commit -m "feat(statement): extract amount calculation for a performance"

2.5 Resolva conflitos localmente
Se o seu desenvolvimento durar mais de algumas horas, é provável que a main remota tenha recebido novos commits de outros membros.
git fetch origin
git rebase origin/main

2.6 Envio (Push) e integração (Pull Request)
git push -u origin <nome-da-sua-branch>

3.FERRAMENTAS DE TESTE
Banco de dados PostgreSQL
A aplicação usa PostgreSQL (as tabelas são criadas automaticamente pelo Hibernate ao subir a aplicação).
O banco pode rodar em um container Docker (opção A) ou ser um PostgreSQL instalado na máquina (opção B).
Nos dois casos a API roda na sua máquina (precisa do JDK 21 ou mais novo) e o .env é o mesmo.

1) Crie o seu arquivo .env na raiz do projeto copiando o modelo e ajustando a senha:

copy .env.example .env      (Windows)
cp .env.example .env        (Linux/Mac)

Variáveis do .env (o arquivo NÃO é versionado, cada dev tem o seu):
DB_PORT     porta do PostgreSQL na sua máquina (padrão 5432)
DB_USERNAME usuário do PostgreSQL (no modelo: postgres)
DB_PASSWORD senha do PostgreSQL
API_PORT    porta da API (padrão 8080)
DB_URL      opcional: URL JDBC completa; se definida, a aplicação ignora DB_PORT

Não use aspas nem o caractere $ nos valores: o .env é lido pela aplicação e também pelo docker compose.
Variáveis de ambiente do sistema com os mesmos nomes também funcionam e têm prioridade sobre o .env.

2A) Banco em Docker (não precisa instalar PostgreSQL):

docker compose up -d

Sobe dois containers: grocery-pg (PostgreSQL 17, já com o banco grocery) e grocery-adminer (o banco no navegador).
A API não vai no Docker: suba pelo terminal (./mvnw spring-boot:run) ou pela IDE, como em 2B.

Banco (DBeaver, psql): host localhost, porta DB_PORT, banco grocery, usuário e senha do .env
Adminer: http://localhost:8082/?pgsql=postgres&username=postgres&db=grocery
  Já vem com sistema, servidor, usuário e banco preenchidos; digite só a senha do .env.
  A porta é ADMINER_PORT no .env (padrão 8082). Dentro do Adminer o servidor é "postgres", não localhost.
  No menu lateral, clicar no nome da tabela abre os dados; a estrutura fica na aba "Show structure".
Swagger (com a API no ar): http://localhost:8080/swagger-ui/index.html

Quando a API termina de subir, ela escreve no log um resumo com a URL do Swagger e a URL de conexão com o banco,
já com as portas do seu .env.

Se a 5432 ou a 8080 já estiverem em uso na sua máquina (outro PostgreSQL ou outro projeto), troque DB_PORT
e/ou API_PORT no .env antes de subir (ex.: DB_PORT=5544, API_PORT=8081).

docker compose logs -f postgres   acompanha o log do banco
docker compose stop               para o banco, mantendo os dados
docker compose down -v            remove o container E APAGA os dados

Usuário e senha só são aplicados quando o banco é criado pela primeira vez. Se mudar DB_USERNAME ou DB_PASSWORD
depois, rode docker compose down -v e suba de novo (isso apaga os dados).

2B) Com PostgreSQL instalado na máquina:
Crie o banco uma única vez (ex.: psql -U postgres, ou pelo DBeaver):

CREATE DATABASE grocery;

Deixe DB_PORT com a porta do seu PostgreSQL (normalmente 5432) e suba a aplicação (precisa do JDK 21 ou mais novo):

./mvnw spring-boot:run       (PowerShell: .\mvnw spring-boot:run)

Os testes automatizados (./mvnw test) também usam o PostgreSQL configurado no .env: o teste de contexto
(GrocerystoreApplicationTests) sobe a aplicação inteira e conecta no banco, então o PostgreSQL precisa estar rodando.

Dados de exemplo (seed)
Popula o banco com dados semi-realistas: 20 categorias, 200 produtos, 100 fornecedores, 3 lojas, 16 funcionários e
2.000 vendas distribuídas em 26 meses entre as lojas, com histórico de preços, pagamentos, compras e perdas de estoque
coerentes entre si (cada loja com o seu estoque). Leva alguns segundos.

./mvnw -Pseed                (PowerShell: .\mvnw -Pseed)

Funciona inclusive com o banco recém-criado e vazio: cria as tabelas e os triggers, popula e encerra sozinho
(sobe o Spring sem servidor web, só para reaproveitar a conexão do .env; leva ~15 s). Não precisa de psql.

Alternativa, direto no banco (a aplicação precisa ter subido ao menos uma vez, para criar tabelas e triggers):
psql -U postgres -d grocery -f src/main/resources/db/seed.sql            (PostgreSQL local)

Com Docker:
docker cp src/main/resources/db/seed.sql grocery-pg:/tmp/seed.sql
docker exec grocery-pg psql -U postgres -d grocery -f /tmp/seed.sql

Se preferir manter a aplicação no ar depois de popular:  ./mvnw spring-boot:run "-Dspring-boot.run.profiles=seed"

- O seed é idempotente: se o banco já tiver lojas, fornecedores, funcionários, preços, vendas ou estoque, ele não faz nada.
- Para refazer do zero (apaga TODOS os dados):
  TRUNCATE sale, payment, sale_items, inventory_movement, inventory, price, product, category, supplier, store, staff, customer, person RESTART IDENTITY CASCADE;
  Ao subir a aplicação de novo o Config.java recria as categorias e o produto básicos, e o seed completa o resto.
  Com Docker, o console do banco abre com: docker exec -it grocery-pg psql -U postgres -d grocery
  (ou recrie tudo com docker compose down -v seguido de docker compose up -d).
- A tabela inventory não é escrita pelo seed: ele só insere inventory_movement e o trigger mantém o estoque.
- As datas acompanham o dia em que o seed roda (a janela de vendas termina em "ontem").

Swagger para testes de endpoints
http://localhost:8080/swagger-ui/index.html

Multi-loja (header X-Store-Id)
A rede tem várias lojas. Cada loja tem os seus funcionários, as suas vendas e o seu estoque; o catálogo (categorias,
produtos e preços), os fornecedores e os clientes são da rede, compartilhados por todas.
  Tabelas com store_id: staff, sale, inventory (uma linha por loja e produto) e inventory_movement.
  payment e sale_items não repetem a loja: chegam a ela pela venda.

As operações de uma loja dizem de qual loja são pelo header X-Store-Id, com o uuid da loja (GET /api/store lista as lojas):
  /api/purchase                          obrigatório: sem ele 400; loja inexistente ou apagada 404.
                                         Uma loja não vê nem apaga as compras de outra (404).
  /api/sale                              obrigatório, como em /api/purchase. Uma loja não vê, não recebe pagamento
                                         nem cancela a venda de outra (404).
  GET /api/staff                         opcional: com ele, só os funcionários da loja; sem ele, a rede inteira.
  /api/criteria/sales e revenue-by-category  opcional: com ele, só a loja; sem ele, a rede inteira.
No Swagger o header aparece como parâmetro da operação. Com curl: -H "X-Store-Id: <uuid da loja>".

Regras (garantidas no service e também no banco, em db/store_constraints.sql):
  - Todo funcionário trabalha numa loja (storeUuid no cadastro), menos o ADMIN, que é da rede e fica sem loja.
    Trocar o storeUuid no PUT transfere o funcionário.
  - O gerente é funcionário da própria loja. Por isso a loja nasce sem gerente: cadastre a loja, depois o funcionário
    nela e então defina o gerente com PUT /api/store/{uuid}. Para transferir o gerente, troque antes o gerente da loja.
  - Loja com funcionários ativos não pode ser apagada.
  - O vendedor trabalha na loja da venda, e a movimentação de uma venda mexe no estoque da loja da venda.

Vendas (/api/sale, header X-Store-Id)
Uma venda tem vários itens e passa por três estados: OPEN (em aberto), COMPLETED (concluída) e CANCELED (cancelada).
  POST /api/sale                      abre a venda (OPEN) com o vendedor e os itens (produto e quantidade). Cada item
                                      leva o preço vigente do produto e precisa ter saldo no estoque da loja, mas o
                                      estoque ainda não baixa. Cada produto aparece em um item só.
  POST /api/sale/{uuid}/payment       registra um pagamento recebido (forma e valor em centavos). Pode pagar em partes
                                      (ex.: PIX + cartão), sem passar do que falta.
  POST /api/sale/{uuid}/cancel        cancela uma venda em aberto: os pagamentos são estornados (REFUNDED).
  GET  /api/sale/{uuid}               a venda com itens, pagamentos, total pago e o que falta pagar.
A venda só é concluída quando é paga integralmente. O pagamento que quita o total muda a venda para COMPLETED e lança
uma movimentação de estoque SALE por item, tudo na mesma transação; o trigger do banco baixa o saldo da loja a cada
uma. Se algum item já não tiver saldo nesse momento (outra venda levou antes), a resposta é 409 e nada é gravado: nem
o pagamento, nem as baixas, e a venda continua em aberto.
Concorrência: a venda e as linhas de estoque dos itens são travadas (SELECT ... FOR UPDATE) até o fim da transação, então
dois pagamentos da mesma venda, ou duas vendas disputando o mesmo saldo, passam um de cada vez. As linhas de estoque
são travadas sempre em ordem de produto, para duas vendas não ficarem esperando uma pela outra (deadlock).
Venda concluída não é cancelada nem recebe pagamento (409): devolução não faz parte deste fluxo.

Banco criado antes da loja entrar no modelo: a aplicação para na subida com uma mensagem pedindo para recriar os dados
(o ddl-auto=update não consegue criar store_id NOT NULL em tabelas que já têm linhas). Rode docker compose down -v,
docker compose up -d e ./mvnw -Pseed, ou o TRUNCATE acima seguido do seed.

Paginação
As listagens (ex.: GET /api/product) são paginadas, nunca devolvem a tabela inteira.
Parâmetros opcionais na URL:
  page   número da página, começando em 0 (padrão 0)
  size   itens por página (padrão 20; acima de 100 é reduzido para 100)
  sort   campo,direção - ex.: sort=name,desc (padrão: name crescente). Pode repetir: sort=category.name&sort=name
         Produtos aceitam: name, barcode, createdAt, category.name. Qualquer outro valor retorna 400 listando os permitidos.
Exemplo: GET /api/product?page=2&size=50&sort=name,asc
Resposta: { "content": [...], "page", "size", "totalElements", "totalPages", "first", "last" }
Padrão e máximo de size ficam em application.properties (spring.data.web.pageable.*).
O id é acrescentado como último critério de ordenação, para a ordem não variar de uma página para outra.

Para paginar outra entidade:
  repository  Page<Entidade> findBy...(Pageable pageable)   (com @EntityGraph se o DTO usar uma relação)
  service     SortPolicy.allowing("campo1", "campo2") define o que o cliente pode ordenar;
              o método faz repository.findBy...(SORT_POLICY.apply(pageable)) e devolve Page<Entidade>
  controller  (@ParameterObject @SortDefault(sort = "campo") Pageable pageable) e
              PageResponse.from(page, Dto::convertToDTO)




