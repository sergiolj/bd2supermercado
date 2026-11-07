package br.edu.ucsal.bd2.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProductController {
	
	
	@GetMapping("/ola")
	public String helloWorld() {
		return "Olá mundo";
		}
}
