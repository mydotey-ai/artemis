package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.EjectionAccessException;
import org.mydotey.ai.artemis.management.domain.EjectionAction;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionStore;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.springframework.dao.DataAccessException;

/**
 * EjectionStore 端口的 MyBatis-Plus 实现（DB 态）。append 后自增主键回填 = sequence
 * （跨重启持久，操作时间线 = id 升序）。DataAccessException 在实现边界翻译为 domain 异常。
 */
public class EjectionStoreImpl implements EjectionStore {

    private final EjectionRecordMapper mapper;

    public EjectionStoreImpl(EjectionRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public EjectionRecord append(EjectionRecord record) {
        EjectionRecordEntity entity = toEntity(record);
        try {
            mapper.insert(entity); // 自增主键回填 entity.id
        } catch (DataAccessException e) {
            throw unavailable("insert", e);
        }
        return toDomain(entity);
    }

    @Override
    public List<EjectionRecord> findAll() {
        List<EjectionRecordEntity> entities;
        try {
            entities = mapper.selectList(
                    Wrappers.<EjectionRecordEntity>lambdaQuery().orderByAsc(EjectionRecordEntity::getId));
        } catch (DataAccessException e) {
            throw unavailable("select", e);
        }
        return entities.stream().map(EjectionStoreImpl::toDomain).toList();
    }

    private static EjectionAccessException unavailable(String action, DataAccessException cause) {
        return new EjectionAccessException(
                EjectionAccessException.EJECTION_STORE_UNAVAILABLE, "ejection store failure: " + action, cause);
    }

    private static EjectionRecordEntity toEntity(EjectionRecord record) {
        EjectionRecordEntity entity = new EjectionRecordEntity();
        entity.setScope(record.target().scope().name());
        if (record.target().scope() == EjectionScope.INSTANCE) {
            entity.setNamespace(record.target().service().namespace());
            entity.setService(record.target().service().service());
            entity.setInstanceId(record.target().instanceId());
        } else {
            entity.setTargetValue(record.target().value());
        }
        entity.setAction(record.action().name());
        entity.setReason(record.reason());
        entity.setOperator(record.operator());
        entity.setCreatedAt(record.timestamp());
        return entity;
    }

    private static EjectionRecord toDomain(EjectionRecordEntity entity) {
        EjectionTarget target = EjectionScope.INSTANCE.name().equals(entity.getScope())
                ? EjectionTarget.instance(
                        new ServiceKey(entity.getNamespace(), entity.getService()), entity.getInstanceId())
                : EjectionTarget.selector(EjectionScope.valueOf(entity.getScope()), entity.getTargetValue());
        return new EjectionRecord(
                target,
                EjectionAction.valueOf(entity.getAction()),
                entity.getReason(),
                entity.getOperator(),
                entity.getCreatedAt(),
                entity.getId());
    }
}
