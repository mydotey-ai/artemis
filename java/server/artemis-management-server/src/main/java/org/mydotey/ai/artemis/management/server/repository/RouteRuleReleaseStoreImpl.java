package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.domain.ReleaseType;
import org.mydotey.ai.artemis.management.domain.RouteRuleException;
import org.mydotey.ai.artemis.management.domain.RouteRuleRelease;
import org.mydotey.ai.artemis.management.domain.RouteRuleReleaseStore;
import org.mydotey.ai.artemis.management.domain.RouteRuleTarget;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.springframework.dao.DataAccessException;

/**
 * RouteRuleReleaseStore 端口的 MyBatis-Plus 实现（DB 态）：append-only（insert，
 * id 自增回填即记录 id）；DataAccessException 在实现边界翻译为 domain 异常。
 */
@Slf4j
public final class RouteRuleReleaseStoreImpl implements RouteRuleReleaseStore {

    /** gson 2.14 对 record 走 canonical constructor，domain record 直接作序列化载体。 */
    private static final Type TARGETS_TYPE = new TypeToken<List<RouteRuleTarget>>() {}.getType();

    private static final Gson GSON = new Gson();

    private final RouteRuleReleaseMapper mapper;

    public RouteRuleReleaseStoreImpl(RouteRuleReleaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RouteRuleRelease append(RouteRuleRelease release) {
        try {
            RouteRuleReleaseEntity entity = toEntity(release);
            mapper.insert(entity); // id 自增回填
            return toDomain(entity);
        } catch (DataAccessException e) {
            throw unavailable("append", e);
        }
    }

    @Override
    public RouteRuleRelease find(ServiceKey service, String name, long id) {
        RouteRuleReleaseEntity entity;
        try {
            entity = mapper.selectOne(Wrappers.<RouteRuleReleaseEntity>lambdaQuery()
                    .eq(RouteRuleReleaseEntity::getId, id)
                    .eq(RouteRuleReleaseEntity::getNamespace, service.namespace())
                    .eq(RouteRuleReleaseEntity::getService, service.service())
                    .eq(RouteRuleReleaseEntity::getRuleName, name));
        } catch (DataAccessException e) {
            throw unavailable("find", e);
        }
        if (entity == null) {
            return null;
        }
        try {
            return toDomain(entity);
        } catch (RuntimeException e) {
            // 回滚源数据损坏显式 503（重试可期），不误报 404
            throw new RouteRuleException(
                    RouteRuleException.STORE_UNAVAILABLE, "corrupted release row: " + entity.getId(), e);
        }
    }

    @Override
    public List<RouteRuleRelease> list(ServiceKey service, String name, int limit) {
        List<RouteRuleReleaseEntity> entities;
        try {
            entities = mapper.selectList(Wrappers.<RouteRuleReleaseEntity>lambdaQuery()
                    .eq(RouteRuleReleaseEntity::getNamespace, service.namespace())
                    .eq(RouteRuleReleaseEntity::getService, service.service())
                    .eq(RouteRuleReleaseEntity::getRuleName, name)
                    .orderByDesc(RouteRuleReleaseEntity::getId)
                    .last("LIMIT " + Math.max(1, limit)));
        } catch (DataAccessException e) {
            throw unavailable("select", e);
        }
        // 坏行跳过 + WARN：append-only 历史数据单行损坏不毒化排障列表（映射异常不属访问异常）
        List<RouteRuleRelease> releases = new ArrayList<>(entities.size());
        for (RouteRuleReleaseEntity entity : entities) {
            try {
                releases.add(toDomain(entity));
            } catch (RuntimeException e) {
                log.warn("skip corrupted release row: id={}", entity.getId(), e);
            }
        }
        return releases;
    }

    private static RouteRuleException unavailable(String action, DataAccessException cause) {
        return new RouteRuleException(
                RouteRuleException.STORE_UNAVAILABLE, "route rule release store failure: " + action, cause);
    }

    private static RouteRuleReleaseEntity toEntity(RouteRuleRelease release) {
        RouteRuleReleaseEntity entity = new RouteRuleReleaseEntity();
        entity.setNamespace(release.service().namespace());
        entity.setService(release.service().service());
        entity.setRuleName(release.name());
        entity.setType(release.type().name());
        entity.setSnapshot(GSON.toJson(release.snapshot(), TARGETS_TYPE));
        entity.setRollbackTo(release.rollbackTo());
        entity.setOperator(release.operator());
        entity.setReleasedAt(release.releasedAt());
        return entity;
    }

    private static RouteRuleRelease toDomain(RouteRuleReleaseEntity entity) {
        if (entity == null) {
            return null;
        }
        List<RouteRuleTarget> snapshot = GSON.fromJson(entity.getSnapshot(), TARGETS_TYPE);
        return new RouteRuleRelease(
                entity.getId(),
                new ServiceKey(entity.getNamespace(), entity.getService()),
                entity.getRuleName(),
                ReleaseType.valueOf(entity.getType()),
                snapshot,
                entity.getRollbackTo(),
                entity.getOperator(),
                entity.getReleasedAt());
    }
}
