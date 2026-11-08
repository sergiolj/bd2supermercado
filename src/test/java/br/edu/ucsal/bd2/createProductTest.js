fetch('http://localhost:8080/api/product', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({
    name: "Feijão Fradinho Kicaldo 1kg",
    description: "Feijão em pacote",
    barCode: "1223456667778",
    price:"29.95"
  })
})
.then(async response => {
  const data = await response.json().catch(() => null);
  console.log(`Status HTTP: ${response.status}`);
  console.log('Corpo da Resposta:', data);
})
.catch(error => console.error('Falha na requisição:', error));