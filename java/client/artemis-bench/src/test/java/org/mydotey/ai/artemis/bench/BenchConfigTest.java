package org.mydotey.ai.artemis.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

/** 档位展开、分片与确定性实例分配（压测数据可信的前提）。 */
class BenchConfigTest {

    @Test
    void should_parse_and_validate_arguments_when_cli_given() {
        BenchConfig config = BenchConfig.parse(new String[] {
            "--role", "registry",
            "--profile", "small",
            "--target", "http://127.0.0.1:8080",
        });
        assertThat(config.services).isEqualTo(500);
        assertThat(config.processes).isEqualTo(50);

        // 覆盖优先于档位值
        config = BenchConfig.parse(new String[] {
            "--role", "registry",
            "--profile", "small",
            "--services", "7",
            "--target", "http://127.0.0.1:8080",
        });
        assertThat(config.services).isEqualTo(7);

        assertThatThrownBy(() -> BenchConfig.parse(new String[] {"--role", "registry", "--services", "x"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BenchConfig.parse(new String[] {"--role", "registry", "--profile", "bogus"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BenchConfig.parse(new String[] {"--role", "registry"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");
        assertThatThrownBy(() -> BenchConfig.parse(new String[] {"--role", "plan", "--shardIndex", "2"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shardIndex");
        assertThatThrownBy(() -> BenchConfig.parse(new String[] {"--role", "plan", "--processes", "0"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void should_expand_profile_when_profile_option_given() {
        // 各档位展开为全维度矩阵（phase 设计文档档位表；默认场景 s1 无 subscriber，预算 = R×reg + disc + k×drv）
        assertProfile("dev", 100, 10, 20, 1, 5, 512, 512, 512, 512, 1536);
        assertProfile("small", 500, 20, 50, 2, 10, 1536, 768, 1024, 512, 4352);
        assertProfile("medium", 1000, 50, 100, 3, 20, 3072, 1024, 1536, 768, 8704);
        assertProfile("large", 1000, 100, 200, 4, 20, 4096, 1536, 1536, 1024, 11776);

        // custom 不展开：必配项缺失即拒绝
        assertThatThrownBy(() -> BenchConfig.parse(new String[] {
                    "--role", "plan", "--profile", "custom",
                }))
                .isInstanceOf(IllegalArgumentException.class);
        BenchConfig custom = BenchConfig.parse(new String[] {
            "--role", "plan",
            "--profile", "custom",
            "--services", "10",
            "--instancesPerService", "5",
            "--processes", "2",
            "--driverJvms", "1",
            "--rampSec", "10",
            "--durationSec", "30",
        });
        assertThat(custom.totalInstances()).isEqualTo(50);

        // s4 场景：subscriber 计入进程清单与预算；s1 不计
        BenchConfig s4 = BenchConfig.parse(new String[] {
            "--role", "subscriber", "--profile", "dev", "--scenario", "s4", "--discoveryTarget", "http://127.0.0.1:8081"
        });
        assertThat(s4.hasSubscriber()).isTrue();
        assertThat(s4.heavyProcessCount()).isEqualTo(1 + 1 + 1 + 1);
        BenchConfig s1 = BenchConfig.parse(new String[] {"--role", "plan", "--profile", "dev"});
        assertThat(s1.hasSubscriber()).isFalse();
        assertThat(s1.heavyProcessCount()).isEqualTo(1 + 1 + 1);

        // plan 输出含资源模型预算（×1.2 余量；s1 无 subscriber：512+512+512）
        assertThat(s1.planEntries())
                .containsEntry("memoryBudgetMb", "1536")
                .containsEntry("memoryBudgetWithMarginMb", "1843");
    }

    private void assertProfile(
            String name,
            int services,
            int instances,
            int processes,
            int driverJvms,
            int subscribers,
            int regHeap,
            int discHeap,
            int drvHeap,
            int subHeap,
            int budget) {
        BenchConfig config = BenchConfig.parse(new String[] {"--role", "plan", "--profile", name});
        assertThat(config.services).isEqualTo(services);
        assertThat(config.instancesPerService).isEqualTo(instances);
        assertThat(config.processes).isEqualTo(processes);
        assertThat(config.driverJvms).isEqualTo(driverJvms);
        assertThat(config.subscribers).isEqualTo(subscribers);
        assertThat(config.registryHeapMb).isEqualTo(regHeap);
        assertThat(config.discoveryHeapMb).isEqualTo(discHeap);
        assertThat(config.driverHeapMb).isEqualTo(drvHeap);
        assertThat(config.subscriberHeapMb).isEqualTo(subHeap);
        assertThat(config.memoryBudgetMb()).isEqualTo(budget);
    }

    @Test
    void should_shard_processes_evenly_when_shard_option_given() {
        // P=50、k=2：两 shard 全覆盖无重叠、大小差 ≤ 1
        List<Integer> all = new ArrayList<>();
        for (int shard = 0; shard < 2; shard++) {
            BenchConfig config = BenchConfig.parse(new String[] {
                "--role", "plan", "--profile", "small", "--shardIndex", String.valueOf(shard), "--shardCount", "2",
            });
            all.addAll(config.shardProcessIds());
        }
        assertThat(all).hasSize(50).doesNotHaveDuplicates();
        assertThat(new HashSet<>(all)).containsAll(range(50));
        BenchConfig shard1 = BenchConfig.parse(new String[] {
            "--role", "plan", "--profile", "small", "--shardIndex", "1", "--shardCount", "2",
        });
        assertThat(shard1.shardProcessIds().size() - 25).isLessThanOrEqualTo(1);

        // 服务不跨 shard：分片进程的服务集合无交集（同进程服务原子归属）
        Set<String> shard0Services = serviceNames(0);
        for (String service : serviceNames(1)) {
            assertThat(shard0Services).doesNotContain(service);
        }
    }

    private Set<String> serviceNames(int shard) {
        Set<String> names = new HashSet<>();
        BenchConfig config = BenchConfig.parse(new String[] {
            "--role", "plan", "--profile", "small", "--shardIndex", String.valueOf(shard), "--shardCount", "2",
        });
        for (int processId : config.shardProcessIds()) {
            for (ServiceId service : config.servicesOfProcess(processId)) {
                names.add(service.getService());
            }
        }
        return names;
    }

    @Test
    void should_assign_instances_deterministically_when_model_generated() {
        BenchConfig config = BenchConfig.parse(new String[] {"--role", "plan", "--profile", "dev"});

        // 完整：全进程实例合集 = S×I，无重复
        List<String> all = new ArrayList<>();
        for (int p = 0; p < config.processes; p++) {
            for (ServiceInstance instance : config.instancesOfProcess(p)) {
                all.add(instance.getService().getService() + "/" + instance.getInstanceId());
            }
        }
        assertThat(all).hasSize(config.totalInstances()).doesNotHaveDuplicates();

        // 确定性：两次生成分配一致
        for (int p = 0; p < config.processes; p++) {
            List<ServiceInstance> first = config.instancesOfProcess(p);
            List<ServiceInstance> second = config.instancesOfProcess(p);
            assertThat(first).hasSameSizeAs(second);
            for (int i = 0; i < first.size(); i++) {
                assertThat(first.get(i).getInstanceId()).isEqualTo(second.get(i).getInstanceId());
                assertThat(first.get(i).getHost()).isEqualTo(second.get(i).getHost());
            }
        }
    }

    private List<Integer> range(int bound) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < bound; i++) {
            ids.add(i);
        }
        return ids;
    }
}
