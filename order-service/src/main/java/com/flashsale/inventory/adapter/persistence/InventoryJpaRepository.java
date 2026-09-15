package com.flashsale.inventory.adapter.persistence;

import com.flashsale.inventory.domain.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface InventoryJpaRepository extends JpaRepository<Inventory, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.flashSaleId = :flashSaleId")
    Optional<Inventory> findByFlashSaleIdForUpdate(Long flashSaleId);

    Optional<Inventory> findByFlashSaleId(Long flashSaleId);
}
