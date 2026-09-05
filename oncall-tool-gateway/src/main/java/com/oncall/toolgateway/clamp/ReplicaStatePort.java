package com.oncall.toolgateway.clamp;

/**
 * 副本状态查询端口（Adapter 模式的抽象侧）。
 *
 * <p>夹紧器只依赖这个接口，不依赖 K8s SDK——这样夹紧逻辑可以脱离集群单测，
 * 换 K8s 客户端也只改 infra 层。
 */
public interface ReplicaStatePort {

    /**
     * 查询当前副本数。
     *
     * @return 当前副本数；查询失败时抛异常（夹紧器会拒绝执行，不允许"查不到就放行"）
     */
    int currentReplicas(String service);
}
