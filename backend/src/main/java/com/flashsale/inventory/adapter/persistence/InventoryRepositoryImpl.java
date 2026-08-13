package com.flashsale.inventory.adapter.persistence;

import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class InventoryRepositoryImpl implements InventoryRepository {

    private final InventoryJpaRepository jpaRepository;

    public InventoryRepositoryImpl(InventoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Inventory> findByFlashSaleIdForUpdate(Long flashSaleId) {
        return jpaRepository.findByFlashSaleIdForUpdate(flashSaleId);
    }

    @Override
    public Optional<Inventory> findByFlashSaleId(Long flashSaleId) {
        return jpaRepository.findByFlashSaleId(flashSaleId);
    }

    @Override
    public Inventory save(Inventory inventory) { return jpaRepository.save(inventory); }
}
