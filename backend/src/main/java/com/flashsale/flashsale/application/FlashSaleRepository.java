package com.flashsale.flashsale.application;

import com.flashsale.flashsale.domain.FlashSale;
import java.util.List;
import java.util.Optional;

public interface FlashSaleRepository {
    List<FlashSale> findAll();
    Optional<FlashSale> findById(Long id);
    FlashSale save(FlashSale flashSale);
}
