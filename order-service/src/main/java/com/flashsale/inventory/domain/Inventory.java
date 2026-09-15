package com.flashsale.inventory.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flash_sale_id", nullable = false, unique = true)
    private Long flashSaleId;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity = 0;

    @Column(name = "sold_quantity", nullable = false)
    private int soldQuantity = 0;

    @Version
    private long version;

    protected Inventory() {}

    public static Inventory initialize(Long flashSaleId, int totalQuantity) {
        Inventory inventory = new Inventory();
        inventory.flashSaleId = flashSaleId;
        inventory.totalQuantity = totalQuantity;
        inventory.availableQuantity = totalQuantity;
        return inventory;
    }

    public boolean hasStock(int quantity) {
        return availableQuantity >= quantity;
    }

    public void sell(int quantity) {
        if (!hasStock(quantity)) {
            throw new IllegalStateException("Not enough stock available for flash sale " + flashSaleId);
        }
        availableQuantity -= quantity;
        soldQuantity += quantity;
    }

    public void release(int quantity) {
        availableQuantity += quantity;
        soldQuantity -= quantity;
    }

    public void resetTo(int totalQuantity) {
        this.totalQuantity = totalQuantity;
        this.availableQuantity = totalQuantity;
    }

    public Long getId() { return id; }
    public Long getFlashSaleId() { return flashSaleId; }
    public int getTotalQuantity() { return totalQuantity; }
    public int getAvailableQuantity() { return availableQuantity; }
    public int getReservedQuantity() { return reservedQuantity; }
    public int getSoldQuantity() { return soldQuantity; }
}
