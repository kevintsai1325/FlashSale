package com.flashsale.purchase.adapter.persistence;

import com.flashsale.purchase.domain.PurchaseRequest;
import com.flashsale.purchase.domain.PurchaseRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseRequestJpaRepository extends JpaRepository<PurchaseRequest, Long> {
    Optional<PurchaseRequest> findByRequestId(UUID requestId);

    // 後台儀表板的統計端點用。只取 createdAt 一欄：分桶只需要時間，
    // 撈整個 entity 會把 24 小時內的每一筆搶購請求都實體化進記憶體。
    long countByStatus(PurchaseRequestStatus status);

    @Query("select p.createdAt from PurchaseRequest p where p.createdAt >= :since")
    List<Instant> findCreatedAtSince(@Param("since") Instant since);
    Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long userId, Long flashSaleId, String idempotencyKey);
    boolean existsByUserIdAndFlashSaleIdAndStatus(Long userId, Long flashSaleId, PurchaseRequestStatus status);
}
