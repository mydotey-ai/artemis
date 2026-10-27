import java.util.List;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.DiscoveryClient;
import org.mydotey.ai.artemis.client.DiscoverySelector;
import org.mydotey.ai.artemis.client.InstanceStatus;
import org.mydotey.ai.artemis.client.RegistryClient;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.client.Subscription;

/**
 * 单文件 SDK 冒烟 harness（部署冒烟用，非交付物）：JDK source-launch 执行
 * （{@code java -cp <client-classpath> SmokeSdk.java <registryHttpUrl> <discoveryHttpUrl> [user pass]}）。
 *
 * <p>覆盖「SDK 走通」最小闭环：注册侧 register（fire-and-forget，最终一致）→ 发现侧 subscribe →
 * 断言本地视图收到该实例。地址参数为 HTTP 寻址入口（ADR-020 前置寻址：SDK 经 HTTP 拉节点目录，
 * 非 gRPC 直连）。配置经系统属性注入（ArtemisClients 缺省 source 含系统属性层）；快照禁用
 * （默认会在 cwd 建目录，冒烟不留痕）。通过 exit 0，超时/失败 exit 1。
 */
public class SmokeSdk {

    private static final String NAMESPACE = "default";
    private static final String SERVICE = "artemis-smoke";
    private static final String INSTANCE_ID = "smoke-instance-1";
    private static final long TIMEOUT_MS = 90_000;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: SmokeSdk <registryHttpUrl> <discoveryHttpUrl> [username password]");
            System.exit(2);
        }
        String username = args.length >= 4 ? args[2] : "artemis";
        String password = args.length >= 4 ? args[3] : "artemis";

        System.setProperty("artemis.client.registry.addresses", args[0]);
        System.setProperty("artemis.client.discovery.addresses", args[1]);
        System.out.println("SDK bootstrap: registry=" + args[0] + " discovery=" + args[1]);
        System.setProperty("artemis.client.auth.username", username);
        System.setProperty("artemis.client.auth.password", password);
        System.setProperty("artemis.client.discovery.snapshot.enabled", "false");

        ServiceId service =
                ServiceId.newBuilder().namespace(NAMESPACE).service(SERVICE).build();
        RegistryClient registry = ArtemisClients.newRegistryClient();
        DiscoveryClient discovery = ArtemisClients.newDiscoveryClient();
        try {
            registry.start();
            registry.register(ServiceInstance.newBuilder()
                    .service(service)
                    .instanceId(INSTANCE_ID)
                    .host("127.0.0.1")
                    .port(8080)
                    .status(InstanceStatus.UP)
                    .weight(100)
                    .build());
            System.out.println("registered " + INSTANCE_ID + " (fire-and-forget, awaiting convergence)");

            discovery.start();
            Subscription subscription =
                    discovery.subscribe(DiscoverySelector.newBuilder().addService(service).build());
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                List<ServiceInstance> instances = subscription.getInstances(service);
                if (instances.stream().anyMatch(i -> INSTANCE_ID.equals(i.getInstanceId()))) {
                    System.out.println("PASS: discovered " + INSTANCE_ID
                            + " via discovery subscription (instances=" + instances.size() + ")");
                    subscription.close();
                    return;
                }
                Thread.sleep(1_000);
            }
            System.err.println("FAIL: instance not visible via discovery within " + TIMEOUT_MS + "ms"
                    + " (instances=" + subscription.getInstances(service).size() + ")");
            subscription.close();
            System.exit(1);
        } finally {
            discovery.close();
            registry.close();
        }
    }
}
