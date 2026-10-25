package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 审计表 mapper（DB 态装配，模式同 AccountMapper）。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {}
