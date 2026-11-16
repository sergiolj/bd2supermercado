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
1) Crie o banco uma única vez (ex.: psql -U postgres, ou pelo DBeaver):

CREATE DATABASE grocery;

2) Crie o seu arquivo .env na raiz do projeto copiando o modelo e ajustando a senha:

copy .env.example .env      (Windows)
cp .env.example .env        (Linux/Mac)

Variáveis do .env (o arquivo NÃO é versionado, cada dev tem o seu):
DB_URL      jdbc:postgresql://localhost:5432/grocery   (opcional, este é o padrão)
DB_USERNAME seu usuário do PostgreSQL
DB_PASSWORD sua senha do PostgreSQL

Variáveis de ambiente do sistema com os mesmos nomes também funcionam e têm prioridade sobre o .env.

Os testes automatizados (./mvnw test) também usam o PostgreSQL configurado no .env: o teste de contexto
(GrocerystoreApplicationTests) sobe a aplicação inteira e conecta no banco, então o PostgreSQL precisa estar rodando.

Dados de exemplo (seed)
Popula o banco com dados semi-realistas: 20 categorias, 200 produtos, 100 fornecedores, 15 funcionários e
2.000 vendas distribuídas em 26 meses, com histórico de preços, pagamentos, compras e perdas de estoque
coerentes entre si. Leva alguns segundos.

./mvnw -Pseed                (PowerShell: .\mvnw -Pseed)

Funciona inclusive com o banco recém-criado e vazio: cria as tabelas e os triggers, popula e encerra sozinho
(sobe o Spring sem servidor web, só para reaproveitar a conexão do .env; leva ~15 s). Não precisa de psql.

Alternativa, direto no banco (a aplicação precisa ter subido ao menos uma vez, para criar tabelas e triggers):
psql -U postgres -d grocery -f src/main/resources/db/seed.sql

Se preferir manter a aplicação no ar depois de popular:  ./mvnw spring-boot:run "-Dspring-boot.run.profiles=seed"

- O seed é idempotente: se o banco já tiver fornecedores, funcionários, preços, vendas ou estoque, ele não faz nada.
- Para refazer do zero (apaga TODOS os dados):
  TRUNCATE sale, payment, sale_items, inventory_movement, inventory, price, product, category, supplier, staff RESTART IDENTITY CASCADE;
  Ao subir a aplicação de novo o Config.java recria as categorias e o produto básicos, e o seed completa o resto.
- A tabela inventory não é escrita pelo seed: ele só insere inventory_movement e o trigger mantém o estoque.
- As datas acompanham o dia em que o seed roda (a janela de vendas termina em "ontem").

Swagger para testes de endpoints
http://localhost:8080/swagger-ui/index.html

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




