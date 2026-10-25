package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.mydotey.ai.artemis.management.domain.Account;
import org.mydotey.ai.artemis.management.domain.AccountAccessException;
import org.mydotey.ai.artemis.management.domain.AccountStore;
import org.springframework.dao.DataAccessException;

/**
 * AccountStore 端口的 MyBatis-Plus 实现（#13 决断首个消费面）。Spring DataAccessException
 * 在实现边界翻译为 domain 异常——core 零 Spring 依赖（enforcer）。
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
    public Optional<Account> findAny() {
        return withStore(() -> selectOrderedByUsername(), "select").stream()
                .findFirst()
                .map(AccountStoreImpl::toDomain);
    }

    @Override
    public void insert(Account account) {
        withStore(() -> mapper.insert(toEntity(account)), "insert");
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

    /** 单管理员语义：字典序首行即结果（脏多行容错）。 */
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
        entity.setCreatedAt(account.createdAt());
        entity.setUpdatedAt(account.updatedAt());
        return entity;
    }

    private static Account toDomain(AccountEntity entity) {
        return new Account(
                entity.getUsername(), entity.getPasswordHash(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
