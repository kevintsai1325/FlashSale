package com.flashsale.flashsale.adapter.persistence;

import com.flashsale.flashsale.domain.FlashSale;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashSaleJpaRepository extends JpaRepository<FlashSale, Long> {}
