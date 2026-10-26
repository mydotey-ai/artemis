package org.mydotey.ai.artemis.management.domain;

/** 发布记录类型：RELEASE = 两段拷贝生效；ROLLBACK = 指向历史版本的反向 release。 */
public enum ReleaseType {
    RELEASE,
    ROLLBACK
}
