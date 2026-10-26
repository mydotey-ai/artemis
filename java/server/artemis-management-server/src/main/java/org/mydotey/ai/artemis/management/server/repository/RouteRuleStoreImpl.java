package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;
import org.mydotey.ai.artemis.management.domain.RouteRule;
import org.mydotey.ai.artemis.management.domain.RouteRuleException;
import org.mydotey.ai.artemis.management.domain.RouteRuleStatus;
import org.mydotey.ai.artemis.management.domain.RouteRuleStore;
import org.mydotey.ai.artemis.management.domain.RouteRuleTarget;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/**
 * RouteRuleStore 端口的 MyBatis-Plus 实现（DB 态）。时间戳由应用层填写、实现照存
 * （update 全列覆盖，含继承的 createdAt）。DataAccessException 在实现边界翻译为
 * domain 异常；insert 并发撞唯一键（select-then-insert 窗口）按已存在语义返回 false。
 */
public final class RouteRuleStoreImpl implements RouteRuleStore {

    /** gson 2.14 对 record 走 canonical constructor，domain record 直接作序列化载体。 */
    private static final Type TARGETS_TYPE = new TypeToken<List<RouteRuleTarget>>() {}.getType();

    private static final Gson GSON = new Gson();

    private final RouteRuleMapper mapper;

    public RouteRuleStoreImpl(RouteRuleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RouteRule find(ServiceKey service, String name) {
        try {
            return toDomain(mapper.selectOne(query(service, name)));
        } catch (DataAccessException e) {
            throw unavailable("find", e);
        }
    }

    @Override
    public List<RouteRule> findAll() {
        List<RouteRuleEntity> entities;
        try {
            entities = mapper.selectList(Wrappers.<RouteRuleEntity>lambdaQuery().orderByAsc(RouteRuleEntity::getId));
        } catch (DataAccessException e) {
            throw unavailable("select", e);
        }
        return entities.stream().map(RouteRuleStoreImpl::toDomain).toList();
    }

    @Override
    public boolean insert(RouteRule rule) {
        try {
            if (mapper.selectOne(query(rule.service(), rule.name())) != null) {
                return false;
            }
            mapper.insert(toEntity(rule));
            return true;
        } catch (DuplicateKeyException e) {
            return false; // select-then-insert 并发窗口撞唯一键，语义同已存在
        } catch (DataAccessException e) {
            throw unavailable("insert", e);
        }
    }

    @Override
    public boolean update(RouteRule rule) {
        try {
            RouteRuleEntity existing = mapper.selectOne(query(rule.service(), rule.name()));
            if (existing == null) {
                return false;
            }
            RouteRuleEntity replacement = toEntity(rule);
            replacement.setId(existing.getId()); // created/updated 均取应用层填写值（含继承的 createdAt）
            // 行数判定：select 与 update 间并发删除（0 行）按缺失返回，与内存态 replace 语义一致
            return mapper.updateById(replacement) > 0;
        } catch (DataAccessException e) {
            throw unavailable("update", e);
        }
    }

    @Override
    public boolean delete(ServiceKey service, String name) {
        try {
            RouteRuleEntity existing = mapper.selectOne(query(service, name));
            if (existing == null) {
                return false;
            }
            // 行数判定：并发先删（0 行）返回 false，调用方不误记审计（与内存态 remove 语义一致）
            return mapper.deleteById(existing.getId()) > 0;
        } catch (DataAccessException e) {
            throw unavailable("delete", e);
        }
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RouteRuleEntity> query(
            ServiceKey service, String name) {
        return Wrappers.<RouteRuleEntity>lambdaQuery()
                .eq(RouteRuleEntity::getNamespace, service.namespace())
                .eq(RouteRuleEntity::getService, service.service())
                .eq(RouteRuleEntity::getName, name);
    }

    private static RouteRuleException unavailable(String action, DataAccessException cause) {
        return new RouteRuleException(
                RouteRuleException.STORE_UNAVAILABLE, "route rule store failure: " + action, cause);
    }

    private static RouteRuleEntity toEntity(RouteRule rule) {
        RouteRuleEntity entity = new RouteRuleEntity();
        entity.setNamespace(rule.service().namespace());
        entity.setService(rule.service().service());
        entity.setName(rule.name());
        entity.setStatus(rule.status().name());
        entity.setTargets(GSON.toJson(rule.targets(), TARGETS_TYPE));
        entity.setOperator(rule.operator());
        entity.setCreatedAt(rule.createdAt());
        entity.setUpdatedAt(rule.updatedAt());
        return entity;
    }

    private static RouteRule toDomain(RouteRuleEntity entity) {
        if (entity == null) {
            return null;
        }
        List<RouteRuleTarget> targets = GSON.fromJson(entity.getTargets(), TARGETS_TYPE);
        return new RouteRule(
                new ServiceKey(entity.getNamespace(), entity.getService()),
                entity.getName(),
                RouteRuleStatus.valueOf(entity.getStatus()),
                targets,
                entity.getOperator(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
