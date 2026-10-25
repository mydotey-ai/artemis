package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceAccessException;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceStore;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.springframework.dao.DataAccessException;

/**
 * LogicalInstanceStore 端口的 MyBatis-Plus 实现（DB 态）。读-改-写由应用层锁串行
 * （单 management 节点），实现内 find-then-write 不设事务。DataAccessException 在实现
 * 边界翻译为 domain 异常。时间戳 = 写入时刻（created 首插、updated 每写刷新），不进领域
 * 模型（审计表已留痕）。
 */
public final class LogicalInstanceStoreImpl implements LogicalInstanceStore {

    private static final Type METADATA_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final Gson GSON = new Gson();

    private final LogicalInstanceMapper mapper;
    private final Clock clock;

    public LogicalInstanceStoreImpl(LogicalInstanceMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public LogicalInstance find(ServiceKey service, String instanceId) {
        try {
            return toDomain(mapper.selectOne(query(service, instanceId)));
        } catch (DataAccessException e) {
            throw unavailable("find", e);
        }
    }

    @Override
    public List<LogicalInstance> findAll() {
        List<LogicalInstanceEntity> entities;
        try {
            entities = mapper.selectList(
                    Wrappers.<LogicalInstanceEntity>lambdaQuery().orderByAsc(LogicalInstanceEntity::getId));
        } catch (DataAccessException e) {
            throw unavailable("select", e);
        }
        return entities.stream().map(LogicalInstanceStoreImpl::toDomain).toList();
    }

    @Override
    public LogicalInstance upsert(LogicalInstance instance) {
        try {
            LogicalInstanceEntity existing = mapper.selectOne(query(instance.service(), keyOf(instance)));
            LogicalInstance previous = toDomain(existing);
            if (existing == null) {
                mapper.insert(toEntity(instance, clock.instant()));
            } else {
                LogicalInstanceEntity replacement = toEntity(instance, clock.instant());
                replacement.setId(existing.getId());
                replacement.setCreatedAt(existing.getCreatedAt()); // created 不随 update 漂移
                mapper.updateById(replacement);
            }
            return previous;
        } catch (DataAccessException e) {
            throw unavailable("upsert", e);
        }
    }

    @Override
    public LogicalInstance delete(ServiceKey service, String instanceId) {
        try {
            LogicalInstanceEntity existing = mapper.selectOne(query(service, instanceId));
            if (existing == null) {
                return null;
            }
            mapper.deleteById(existing.getId());
            return toDomain(existing);
        } catch (DataAccessException e) {
            throw unavailable("delete", e);
        }
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LogicalInstanceEntity> query(
            ServiceKey service, String instanceId) {
        return Wrappers.<LogicalInstanceEntity>lambdaQuery()
                .eq(LogicalInstanceEntity::getNamespace, service.namespace())
                .eq(LogicalInstanceEntity::getService, service.service())
                .eq(LogicalInstanceEntity::getInstanceId, instanceId);
    }

    private static String keyOf(LogicalInstance instance) {
        return instance.instance().instanceId();
    }

    private static LogicalInstanceAccessException unavailable(String action, DataAccessException cause) {
        return new LogicalInstanceAccessException(
                LogicalInstanceAccessException.LOGICAL_INSTANCE_STORE_UNAVAILABLE,
                "logical instance store failure: " + action,
                cause);
    }

    private static LogicalInstanceEntity toEntity(LogicalInstance instance, java.time.Instant now) {
        LogicalInstanceEntity entity = new LogicalInstanceEntity();
        entity.setNamespace(instance.service().namespace());
        entity.setService(instance.service().service());
        entity.setInstanceId(instance.instance().instanceId());
        entity.setHost(instance.instance().host());
        entity.setPort(instance.instance().port());
        entity.setStatus(instance.instance().status().name());
        entity.setWeight(instance.instance().weight());
        entity.setMetadata(GSON.toJson(instance.instance().metadata(), METADATA_TYPE));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private static LogicalInstance toDomain(LogicalInstanceEntity entity) {
        if (entity == null) {
            return null;
        }
        Instance instance = new Instance(
                entity.getInstanceId(),
                entity.getHost(),
                entity.getPort(),
                InstanceStatus.valueOf(entity.getStatus()),
                entity.getWeight(),
                GSON.fromJson(entity.getMetadata(), METADATA_TYPE));
        return new LogicalInstance(new ServiceKey(entity.getNamespace(), entity.getService()), instance);
    }
}
