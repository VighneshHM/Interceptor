package com.proxy.interceptor.service;

import com.proxy.interceptor.dto.PendingQuery;
import com.proxy.interceptor.model.*;
import com.proxy.interceptor.repository.BlockedQueryRepository;
import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class BlockedQueryService {

    private final BlockedQueryRepository blockedQueryRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AuditService auditService;

    @Value("${approval.peer-enabled}")
    private boolean peerApprovalEnabled;

    @Value("${approval.min-votes}")
    private int minVotes;

    // In-memory store for pending queries with their callbacks
    private final ConcurrentHashMap<Long, PendingQuery> pendingQueries = new ConcurrentHashMap<>();

    @Transactional
    public void addBlockedQuery(String connId,
                                String queryType,
                                String sql,
                                ByteBuf originalMessage,
                                Consumer<ByteBuf> forwardCallback,
                                Consumer<String> rejectCallback) {

        // Generate nonce for replay protection
        String nonce = UUID.randomUUID().toString();

        // Save to database
        BlockedQuery query = BlockedQuery.builder()
                .connId(connId)
                .queryType(QueryType.valueOf(queryType))
                .queryPreview(sql.length() > 4000 ? sql.substring(0, 4000) : sql)
                .requiresPeerApproval(peerApprovalEnabled)
                .nonce(nonce)
                .build();

        query = blockedQueryRepository.save(query);

        // Store in memory for callbacks
        PendingQuery pending = new PendingQuery(
                query.getId(),
                connId,
                originalMessage,
                forwardCallback,
                rejectCallback,
                ConcurrentHashMap.newKeySet(),
                ConcurrentHashMap.newKeySet()
        );
        pendingQueries.put(query.getId(), pending);

        // Publish notification to Redis for real-time updates
        publishBlockedNotification(query);

        log.info("Blocked query #{} from {}: {}", query.getId(), connId, sql.substring(0, Math.min(50, sql.length())));
    }

    @Transactional
    public boolean approveQuery(Long id, String approvedBy) {
        PendingQuery pending = pendingQueries.get(id);
        if (pending == null) {
            log.error("Approve failed: query #{} not found in pending", id);
            return false;
        }

        BlockedQuery query = blockedQueryRepository.findById(id).orElse(null);
        if (query == null || query.getStatus() != Status.PENDING) {
            log.error("Approve failed: query #{} not in pending status", id);
            return false;
        }

        // Update database
        query.setStatus(Status.APPROVED);
        query.setResolvedAt(Instant.now());
        query.setResolvedBy(approvedBy);
        blockedQueryRepository.save(query);

        // Forward the original query to PostgreSQL
        pending.forwardCallback().accept(pending.originalMessage());
        pendingQueries.remove(id);

        // Audit
        auditService.log(approvedBy, "query_approved",
                String.format("Query $%d approved: %s", id, query.getQueryPreview()), null);

        // Publish approval notification
        publishApprovalNotification(query, "APPROVED", approvedBy);

        log.info("Query #{} approved by {}", id, approvedBy);
        return true;
    }

    @Transactional
    public boolean rejectQuery(Long id, String rejectedBy) {
        PendingQuery pending = pendingQueries.get(id);
        if (pending == null) {
            log.warn("Reject failed: query #{} not found in pending", id);
            return false;
        }

        BlockedQuery query = blockedQueryRepository.findById(id).orElse(null);
        if (query == null || query.getStatus() != Status.PENDING) {
            log.warn("Reject failed: query #{} not in pending status", id);
            return false;
        }

        // Update database
        query.setStatus(Status.REJECTED);
        query.setResolvedAt(Instant.now());
        query.setResolvedBy(rejectedBy);
        blockedQueryRepository.save(query);

        // Send error response to client
        pending.rejectCallback().accept("Query rejected by " + rejectedBy);
        pending.originalMessage().release();
        pendingQueries.remove(id);

        // Audit
        auditService.log(rejectedBy, "query_rejected",
                String.format("Query #%d rejected: %s", id, query.getQueryPreview()), null);

        // Publish rejection notification
        publishApprovalNotification(query, "REJECTED", rejectedBy);

        log.info("Query #{} rejected by {}", id, rejectedBy);
        return true;
    }

    @Transactional
    public Map<String, Object> addVote(Long id, String username, String vote) {
        PendingQuery pending = pendingQueries.get(id);
        if (pending == null) {
            log.warn("Vote failed: query #{} not found in pending", id);
            return Map.of("success", false, "error", "Query not found");
        }

        BlockedQuery query = blockedQueryRepository.findById(id).orElse(null);
        if (query == null || !query.isRequiresPeerApproval()) {
            log.warn("Vote failed: query #{} does not require peer approval", id);
            return Map.of("success", false, "error", "Query does not require peer approval");
        }

        // Record vote
        if ("approve".equalsIgnoreCase(vote)) {
            pending.approvals().add(username);
            pending.rejections().remove(username);
        } else if ("reject".equalsIgnoreCase(vote)) {
            pending.rejections().add(username);
            pending.approvals().remove(username);
        } else {
            return Map.of("success", false, "error", "Invalid vote type");
        }

        // Update counts in database
        query.setApprovalCount(pending.approvals().size());
        query.setRejectionCount(pending.rejections().size());

        // Save individual vote
        QueryApproval approval = QueryApproval.builder()
                .blockedQuery(query)
                .username(username)
                .vote(Vote.valueOf(vote.toUpperCase()))
                .build();
        query.getApprovals().add(approval);
        blockedQueryRepository.save(query);

        // Check threshold
        if (pending.approvals().size() >= minVotes) {
            approveQuery(id, "Peer Approval System");
            return Map.of("success", true, "autoResolved", true, "action", "approved");
        }

        if (pending.rejections().size() >= minVotes) {
            rejectQuery(id, "Peer Approval System");
            return Map.of("success", true, "autoResolved", true, "action", "rejected");
        }

        // Publish vote notification
        publishVoteNotification(id, username, vote, pending.approvals().size(), pending.rejections().size());

        return Map.of(
                "success", true,
                "autoResolved", false,
                "approvalCount", pending.approvals().size(),
                "rejectionCount", pending.rejections().size()
        );
    }

    public List<BlockedQuery> getPendingQueries() {
        return blockedQueryRepository.findByStatusOrderByCreatedAtAsc(Status.PENDING);
    }

    public List<BlockedQuery> getAllQueries() {
        return blockedQueryRepository.findTop100ByOrderByCreatedAtDesc();
    }

    public void cleanupConnection(String connId) {
        pendingQueries.entrySet().removeIf(entry -> {
            if (entry.getValue().connId().equals(connId)) {
                entry.getValue().originalMessage().release();
                log.info("Cleaned up pending query #{} for disconnected connection {}",
                        entry.getKey(), connId);
                return true;
            }
            return false;
        });
    }

    public Map<String, Object> getVoteStatus(Long id) {
        PendingQuery pending = pendingQueries.get(id);
        if (pending == null) {
            return null;
        }
        return Map.of(
                "id", id,
                "approvals", new ArrayList<>(pending.approvals()),
                "rejections", new ArrayList<>(pending.rejections()),
                "approvalCount", pending.approvals().size(),
                "rejectionCount", minVotes
        );
    }

    private void publishBlockedNotification(BlockedQuery query) {
        try {
            redisTemplate.convertAndSend("interceptor:blocked", Map.of(
                    "type", "Blocked",
                    "queryId", query.getId(),
                    "connId", query.getConnId(),
                    "queryType", query.getQueryType().name(),
                    "preview", query.getQueryPreview().substring(0, Math.min(200, query.getQueryPreview().length())),
                    "requiresPeerApproval", query.isRequiresPeerApproval(),
                    "timestamp", Instant.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to publish blocked notification: {}", e.getMessage());
        }
    }

    private void publishApprovalNotification(BlockedQuery query, String action, String resolveBy) {
        try {
            redisTemplate.convertAndSend("interceptor:approvals", Map.of(
                    "type", action,
                    "queryId", query.getId(),
                    "resolvedBy", resolveBy,
                    "timestamp", Instant.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to publish approval notification: {}", e.getMessage());
        }
    }

    private void publishVoteNotification(Long queryId, String username, String vote, int approvals, int rejections) {
        try {
            redisTemplate.convertAndSend("interceptor:votes", Map.of(
                    "type", "VOTE",
                    "queryId", queryId,
                    "username", username,
                    "vote", vote,
                    "approvalCount", approvals,
                    "rejectionCount", rejections,
                    "timestamp", Instant.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to publish vote notification: {}", e.getMessage());
        }
    }
}
