package com.flashsale.flashsale.adapter.persistence;

import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class FlashSaleRepositoryImpl implements FlashSaleRepository {

    private final FlashSaleJpaRepository jpaRepository;

    public FlashSaleRepositoryImpl(FlashSaleJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<FlashSale> findAll() { return jpaRepository.findAll(); }

    @Override
    public Optional<FlashSale> findById(Long id) { return jpaRepository.findById(id); }

    @Override
    public FlashSale save(FlashSale flashSale) { return jpaRepository.save(flashSale); }

    @Override
    public void flush() { jpaRepository.flush(); }
}
