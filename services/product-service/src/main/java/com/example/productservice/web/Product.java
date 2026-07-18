package com.example.productservice.web;

/** Minimal domain record for the demo API. */
public record Product(Long id, String name, String category, double price) {
}
