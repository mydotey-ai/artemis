package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 发布记录表 mapper（DB 态装配，模式同 RouteRuleMapper）。 */
@Mapper
public interface RouteRuleReleaseMapper extends BaseMapper<RouteRuleReleaseEntity> {}
