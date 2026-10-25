package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 逻辑实例表 mapper（DB 态装配，模式同 EjectionRecordMapper）。 */
@Mapper
public interface LogicalInstanceMapper extends BaseMapper<LogicalInstanceEntity> {}
