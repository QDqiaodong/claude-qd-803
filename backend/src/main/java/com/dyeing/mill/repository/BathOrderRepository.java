package com.dyeing.mill.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dyeing.mill.domain.BathOrder;

public interface BathOrderRepository extends JpaRepository<BathOrder, Long> {

    /** 下达加悲观行锁：两名人员几乎同时下达同一布车时，在这把锁上排队 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BathOrder b where b.id = :id")
    Optional<BathOrder> findByIdForUpdate(@Param("id") Long id);

    @Query("select b from BathOrder b order by b.id desc")
    List<BathOrder> findAllOrderByIdDesc();

    @Query("select b from BathOrder b where b.status = :status order by b.id desc")
    List<BathOrder> findByStatus(@Param("status") String status);

    /** 某布车某排缸批次的待确认单（业务层防重，数据库唯一键兜底） */
    @Query("select b from BathOrder b where b.cartId = :cartId and b.status = 'PENDING' order by b.id desc")
    List<BathOrder> findPendingByCart(@Param("cartId") Long cartId);

    /** 该布车当前排缸批次是否已有已下达单 */
    @Query("select b from BathOrder b where b.cartId = :cartId and b.status = 'ISSUED' "
            + "and (b.assignToken = :token or (b.assignToken is null and :token is null))")
    List<BathOrder> findIssuedByCartAndToken(@Param("cartId") Long cartId, @Param("token") String token);

    long countByOrderNo(String orderNo);
}
