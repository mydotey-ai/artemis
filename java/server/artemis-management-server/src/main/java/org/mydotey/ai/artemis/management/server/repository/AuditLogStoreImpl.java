package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.AuditLogStore;
import org.mydotey.ai.artemis.management.domain.EjectionAccessException;
import org.springframework.dao.DataAccessException;

/**
 * AuditLogStore 端口的 MyBatis-Plus 实现（DB 态）。时间倒序 = id 降序。
 */
public class AuditLogStoreImpl implements AuditLogStore {

    private final AuditLogMapper mapper;

    public AuditLogStoreImpl(AuditLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AuditLog append(AuditLog log) {
        AuditLogEntity entity = toEntity(log);
        try {
            mapper.insert(entity); // 自增主键回填 entity.id
        } catch (DataAccessException e) {
            throw new EjectionAccessException(
                    EjectionAccessException.EJECTION_STORE_UNAVAILABLE, "audit store failure: insert", e);
        }
        return toDomain(entity);
    }

    @Override
    public List<AuditLog> find(String operation, int limit) {
        List<AuditLogEntity> entities;
        try {
            var query = Wrappers.<AuditLogEntity>lambdaQuery();
            if (operation != null && !operation.isBlank()) {
                query.eq(AuditLogEntity::getOperation, operation);
            }
            query.orderByDesc(AuditLogEntity::getId).last("LIMIT " + Math.max(1, limit));
            entities = mapper.selectList(query);
        } catch (DataAccessException e) {
            throw new EjectionAccessException(
                    EjectionAccessException.EJECTION_STORE_UNAVAILABLE, "audit store failure: select", e);
        }
        return entities.stream().map(AuditLogStoreImpl::toDomain).toList();
    }

    private static AuditLogEntity toEntity(AuditLog log) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setOperation(log.operation());
        entity.setTarget(log.target());
        entity.setOperator(log.operator());
        entity.setResult(log.result());
        entity.setDetail(log.detail());
        entity.setCreatedAt(log.timestamp());
        return entity;
    }

    private static AuditLog toDomain(AuditLogEntity entity) {
        return new AuditLog(
                entity.getOperation(),
                entity.getTarget(),
                entity.getOperator(),
                entity.getResult(),
                entity.getDetail(),
                entity.getCreatedAt(),
                entity.getId());
    }
}
