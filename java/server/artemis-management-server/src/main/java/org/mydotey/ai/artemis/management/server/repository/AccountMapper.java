package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 账户表 mapper：@Mapper 经 MyBatis-Plus 自动配置扫描（DB 态——DataSource bean 存在时
 * autoconfig 才激活；内存态零注册零实例化）。
 */
@Mapper
public interface AccountMapper extends BaseMapper<AccountEntity> {}
