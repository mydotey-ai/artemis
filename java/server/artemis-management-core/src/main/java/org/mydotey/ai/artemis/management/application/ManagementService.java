package org.mydotey.ai.artemis.management.application;

import java.util.List;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStatus;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 管理面用例（ADR-016）：有效视图 = registry 事实 + management 摘除记录。
 */
public interface ManagementService {

    List<ServiceView> listServices(String namespace);

    ServiceDetail getService(ServiceKey key, boolean includeEjected);

    EjectionView eject(ServiceKey key, String instanceId, String reason);

    void recover(ServiceKey key, String instanceId);

    LeaseStatus leaseStatus(String namespace, String service);

    SelfProtectionStatus selfProtectionStatus(String namespace, String service, boolean includeNormal);

    List<ConfigProperty> configStatus();
}
