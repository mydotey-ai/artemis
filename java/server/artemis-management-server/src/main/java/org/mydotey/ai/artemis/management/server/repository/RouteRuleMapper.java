package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 路由规则表 mapper（DB 态装配，模式同 LogicalInstanceMapper）。 */
@Mapper
public interface RouteRuleMapper extends BaseMapper<RouteRuleEntity> {}
