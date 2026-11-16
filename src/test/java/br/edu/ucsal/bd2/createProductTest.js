fetch('http://localhost:8080/api/product', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({
    name: "Feijão Fradinho Kicaldo 1kg",
    categoryId: 5,
    barcode: "1223456667778",
    description: "Feijão em pacote",
    unitOfMeasure: "UNIT"
  })
})
.then(async response => {
  const data = await response.json().catch(() => null);
  console.log(`Status HTTP: ${response.status}`);
  console.log('Corpo da Resposta:', data);
})
.catch(error => console.error('Falha na requisição:', error));
