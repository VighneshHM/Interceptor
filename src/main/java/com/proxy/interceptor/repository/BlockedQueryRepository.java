package com.proxy.interceptor.repository;

import com.proxy.interceptor.model.BlockedQuery;
import com.proxy.interceptor.model.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface BlockedQueryRepository extends JpaRepository<BlockedQuery, Long> {

    List<BlockedQuery> findByStatusOrderByCreatedAtAsc(Status status);

    List<BlockedQuery> findByConnIdAndStatusOrderByCreatedAtDesc(String connId, Status status);

    Optional<BlockedQuery> findByNonce(String nonce);

    @Query("SELECT bq FROM BlockedQuery bq WHERE bq.status = 'PENDING' AND bq.createdAt < :expireTime")
    List<BlockedQuery> findExpiredPendingQueries(Instant expireTime);

    List<BlockedQuery> findTop100ByOrderByCreatedAtDesc();
}
