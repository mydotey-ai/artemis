package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.mydotey.ai.artemis.management.domain.Account;
import org.mydotey.ai.artemis.management.domain.AccountAccessException;
import org.mydotey.ai.artemis.management.domain.AccountAdminException;
import org.mydotey.ai.artemis.management.domain.AccountStore;
import org.mydotey.ai.artemis.management.domain.Role;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/**
 * AccountStore 端口的 MyBatis-Plus 实现（v1.0 phase-2 多账户）。Spring DataAccessException
 * 在实现边界翻译为 domain 异常——core 零 Spring 依赖（enforcer）；唯一键冲突翻译
 * ALREADY_EXISTS（并发建户竞态）。
 */
public class AccountStoreImpl implements AccountStore {

    private final AccountMapper mapper;

    public AccountStoreImpl(AccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean existsAny() {
        return !withStore(() -> selectOrderedByUsername(), "select").isEmpty();
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return Optional.ofNullable(withStore(() -> mapper.selectById(username), "select"))
                .map(AccountStoreImpl::toDomain);
    }

    @Override
    public List<Account> list() {
        return withStore(() -> selectOrderedByUsername(), "select").stream()
                .map(AccountStoreImpl::toDomain)
                .toList();
    }

    @Override
    public void insert(Account account) {
        try {
            mapper.insert(toEntity(account));
        } catch (DuplicateKeyException e) {
            throw new AccountAdminException(
                    AccountAdminException.ALREADY_EXISTS, "account already exists: " + account.username());
        } catch (DataAccessException e) {
            throw new AccountAccessException(
                    AccountAccessException.ACCOUNT_STORE_UNAVAILABLE, "account store failure: insert", e);
        }
    }

    @Override
    public void updatePasswordHash(String username, String passwordHash, Instant updatedAt) {
        withStore(
                () -> mapper.update(
                        null,
                        Wrappers.<AccountEntity>lambdaUpdate()
                                .eq(AccountEntity::getUsername, username)
                                .set(AccountEntity::getPasswordHash, passwordHash)
                                .set(AccountEntity::getUpdatedAt, updatedAt)),
                "updatePasswordHash");
    }

    @Override
    public void updateRole(String username, Role role, Instant updatedAt) {
        withStore(
                () -> mapper.update(
                        null,
                        Wrappers.<AccountEntity>lambdaUpdate()
                                .eq(AccountEntity::getUsername, username)
                                .set(AccountEntity::getRole, role.name())
                                .set(AccountEntity::getUpdatedAt, updatedAt)),
                "updateRole");
    }

    @Override
    public void delete(String username) {
        withStore(() -> mapper.deleteById(username), "delete");
    }

    private List<AccountEntity> selectOrderedByUsername() {
        return mapper.selectList(Wrappers.<AccountEntity>lambdaQuery().orderByAsc(AccountEntity::getUsername));
    }

    private static <T> T withStore(Supplier<T> action, String actionName) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            throw new AccountAccessException(
                    AccountAccessException.ACCOUNT_STORE_UNAVAILABLE, "account store failure: " + actionName, e);
        }
    }

    private static AccountEntity toEntity(Account account) {
        AccountEntity entity = new AccountEntity();
        entity.setUsername(account.username());
        entity.setPasswordHash(account.passwordHash());
        entity.setRole(account.role().name());
        entity.setCreatedAt(account.createdAt());
        entity.setUpdatedAt(account.updatedAt());
        return entity;
    }

    private static Account toDomain(AccountEntity entity) {
        return new Account(
                entity.getUsername(),
                entity.getPasswordHash(),
                Role.valueOf(entity.getRole()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
