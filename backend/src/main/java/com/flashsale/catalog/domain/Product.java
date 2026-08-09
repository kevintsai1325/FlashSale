package com.flashsale.catalog.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    protected Product() {}

    private Product(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public static Product create(String name, String description) {
        return new Product(name, description);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
