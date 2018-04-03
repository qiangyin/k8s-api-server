package com.cloudzone.k8s.service.common;

import io.fabric8.kubernetes.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 获取k8s集群的连接
 *
 * @author leiyuanjie
 * @date 2018/03/12
 */

public class K8sUtils {
    public static final String DEPLOYMENT = "Deployment";
    public static final String API_VERSION = "extensions/v1beta1";
    public static final String SERVICE_API_VERSION = "v1";
    public static final String DEFAULT_NAMESPACE = "default";
    public static final String NODE_PORT = "NodePort";
    public static final String IF_NOT_PRESENT = "IfNotPresent";
    public static final String KEY = "name";
    public static final int DEFAULT_REPLICAS = 1;
    public static final String SERVICE = "Service";
    public static final String HPA_APIVERSION = "autoscaling/v1";
    public static final String HPA_KIND = "HorizontalPodAutoscaler";

    private static final Logger LOGGER = LoggerFactory.getLogger(K8sUtils.class);

    private volatile static KubernetesClient client = null;

    public static KubernetesClient getKubernetesClient(String masterUrl) {
        if (client == null) {
            synchronized (K8sUtils.class) {
                if (client == null) {
                    Config config = new ConfigBuilder().withMasterUrl(masterUrl).withTrustCerts(true).build();
                    try {
                        client = new DefaultKubernetesClient(config);
                        LOGGER.info("获取k8s集群client成功!");
                    } catch (KubernetesClientException e) {
                        LOGGER.info("获取k8s集群连接异常!");
                        e.printStackTrace();
                    }
                }
            }
        }

        return client;
    }
}
