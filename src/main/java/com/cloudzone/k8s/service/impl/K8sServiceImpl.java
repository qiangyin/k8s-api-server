package com.cloudzone.k8s.service.impl;

import com.cloudzone.K8sServiceAPI;
import com.cloudzone.common.entity.k8s.*;
import com.cloudzone.k8s.service.common.K8sUtils;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.text.SimpleDateFormat;
import java.util.*;

import static jdk.nashorn.internal.objects.NativeArray.lastIndexOf;
import static jdk.nashorn.internal.objects.NativeString.substring;

/**
 * k8s service Impl
 *
 * @author leiyuanjie
 * @date 2018/03/12
 */
@Service
public class K8sServiceImpl implements K8sServiceAPI {

    @Value("${k8s.api.server.masterUrl}")
    private String masterUrl;

    @Value("${k8s.api.server.image}")
    private String imageUrl;

    private static final Logger LOGGER = LoggerFactory.getLogger(K8sServiceImpl.class);

    @Override
    public void createDeploymentAndService(@RequestBody DeploymentVo deploymentVo) {
        LOGGER.info("进入k8s, 开始获取连接");
        // 获取连接
        KubernetesClient client = K8sUtils.getKubernetesClient(masterUrl);

        // 创建Deployment
        createDeployment(client, deploymentVo);
        // 创建服务
        createService(client, deploymentVo);
        // 创建HPA
        createOrReplaceHorizontalPodAutoscaler(client, deploymentVo);

    }

    /**
     * 创建Deployment实例
     *
     * @param client
     * @param deploymentVo
     */
    public void createDeployment(KubernetesClient client, DeploymentVo deploymentVo) {
        LOGGER.info("****************开始创建Deployment**************");

        Deployment deployment = new Deployment();
        deployment.setKind(deploymentVo.getDeployMode());
        deployment.setApiVersion(K8sUtils.API_VERSION);

        // 设置Deployment的metadata
        ObjectMeta meta = new ObjectMeta();
        meta.setName(deploymentVo.getServiceName());
        meta.setNamespace(K8sUtils.DEFAULT_NAMESPACE);
        Map<String, String> labelMap = new HashMap<>(16);
        labelMap.put(K8sUtils.KEY, deploymentVo.getServiceName());
        meta.setLabels(labelMap);
        deployment.setMetadata(meta);

        // 设置Deployment的replicas和selector
        DeploymentSpec deploymentSpec = new DeploymentSpec();
        deploymentSpec.setReplicas(K8sUtils.DEFAULT_REPLICAS);
        LabelSelector labelSelector = new LabelSelector();
        labelSelector.setMatchLabels(labelMap);
        deploymentSpec.setSelector(labelSelector);

        // 设置pod模板下面的meta
        PodTemplateSpec podTemplateSpec = new PodTemplateSpec();
        ObjectMeta objectMeta = new ObjectMeta();
        objectMeta.setLabels(labelMap);
        podTemplateSpec.setMetadata(objectMeta);

        // 设置pod模板下面的spec
        PodSpec podSpec = new PodSpec();
        Container container = new Container();
        container.setName(deploymentVo.getServiceName());
        container.setImagePullPolicy("Always");
        // TODO 镜像地址需要确定
        container.setImage(imageUrl + deploymentVo.getImageName() + ":" + deploymentVo.getImageVersion());

        // 设置容器端口信息
        ContainerPort containerPort = new ContainerPort();
        containerPort.setContainerPort(deploymentVo.getPort());
        List<ContainerPort> containerPorts = new ArrayList<>();
        containerPorts.add(containerPort);
        container.setPorts(containerPorts);

        // 申请资源
        ResourceRequirements resourceRequireents = new ResourceRequirements();
        Map<String, Quantity> requestSourceMap = new HashMap<>(16);
        // 格式化参数
        String cpu = String.valueOf(deploymentVo.getCpu());
        String memory = String.valueOf(deploymentVo.getMemory()) + "Gi";

        requestSourceMap.put("cpu", new Quantity(cpu));
        requestSourceMap.put("memory", new Quantity(memory));
        resourceRequireents.setRequests(requestSourceMap);
        resourceRequireents.setLimits(requestSourceMap);
        container.setResources(resourceRequireents);

        List<Container> containers = new ArrayList<>();
        containers.add(container);
        podSpec.setContainers(containers);

        podTemplateSpec.setSpec(podSpec);
        deploymentSpec.setTemplate(podTemplateSpec);
        deployment.setSpec(deploymentSpec);

        // 创建实例
        client.extensions().deployments().create(deployment);

        LOGGER.info("*******************Deployment创建完成****************");
    }

    /**
     * 创建Service, 供外部访问使用
     *
     * @param client
     * @param deploymentVo
     */
    public void createService(KubernetesClient client, DeploymentVo deploymentVo) {
        LOGGER.info("*******开始创建服务*****, ServiceName={}", deploymentVo.getServiceName());

        io.fabric8.kubernetes.api.model.Service service = new io.fabric8.kubernetes.api.model.Service();
        service.setKind(K8sUtils.SERVICE);
        service.setApiVersion(K8sUtils.SERVICE_API_VERSION);

        // 设置Service的meta
        ObjectMeta objectMeta = new ObjectMeta();
        objectMeta.setName(deploymentVo.getServiceName());
        objectMeta.setNamespace(K8sUtils.DEFAULT_NAMESPACE);
        Map<String, String> labelMap = new HashMap<>(16);
        labelMap.put(K8sUtils.KEY, deploymentVo.getServiceName());
        objectMeta.setLabels(labelMap);
        service.setMetadata(objectMeta);

        // 设置Service的spec
        ServiceSpec serviceSpec = new ServiceSpec();
        ServicePort servicePort = new ServicePort();

        servicePort.setPort(deploymentVo.getPort());
        servicePort.setTargetPort(new IntOrString(deploymentVo.getPort()));
        servicePort.setNodePort(deploymentVo.getNodePort());

        List<ServicePort> ports = new ArrayList<>();
        ports.add(servicePort);
        serviceSpec.setPorts(ports);
        serviceSpec.setType(K8sUtils.NODE_PORT);
        serviceSpec.setSelector(labelMap);
        service.setSpec(serviceSpec);

        // 创建服务
        client.services().create(service);

        LOGGER.info("*******************创建服务完成******************");
    }

    /**
     * 创建弹性伸缩
     *
     * @params
     * @author luocheng
     * @since 2018/3/13
     */
    public void createOrReplaceHorizontalPodAutoscaler(KubernetesClient client, DeploymentVo deploymentVo) {
        LOGGER.info("*******开始创建弹性伸缩*****");
        HorizontalPodAutoscaler horizontalPodAutoscaler = horizontalPodAutoscalerVoToHorizontalPodAutoscaler(deploymentVo);
        client.autoscaling().horizontalPodAutoscalers().createOrReplace(horizontalPodAutoscaler);

        LOGGER.info("*******************弹性伸缩创建完毕******************");
    }

    /**
     * HorizontalPodAutoscalerVo转HorizontalPodAutoscaler
     *
     * @param deploymentVo
     * @return HorizontalPodAutoscaler
     * @author luocheng
     * @since 2018/3/13
     */
    public HorizontalPodAutoscaler horizontalPodAutoscalerVoToHorizontalPodAutoscaler(DeploymentVo deploymentVo) {
        HorizontalPodAutoscaler horizontalPodAutoscaler = new HorizontalPodAutoscaler();

        // 设置apiVersion和kind
        horizontalPodAutoscaler.setApiVersion(K8sUtils.HPA_APIVERSION);
        horizontalPodAutoscaler.setKind(K8sUtils.HPA_KIND);

        // 设置metadata
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(deploymentVo.getServiceName());
        metadata.setNamespace(K8sUtils.DEFAULT_NAMESPACE);

        // 设置spec
        HorizontalPodAutoscalerSpec spec = new HorizontalPodAutoscalerSpec();
        spec.setMaxReplicas(deploymentVo.getMaxReplicas());
        spec.setMinReplicas(deploymentVo.getMinReplicas());
        CrossVersionObjectReference scaleRef = new CrossVersionObjectReference();
        scaleRef.setName(deploymentVo.getServiceName());
        scaleRef.setApiVersion(K8sUtils.API_VERSION);
        scaleRef.setKind(deploymentVo.getDeployMode());
        spec.setScaleTargetRef(scaleRef);
        spec.setTargetCPUUtilizationPercentage(deploymentVo.getTargetCPUUtilizationPercentage());

        horizontalPodAutoscaler.setMetadata(metadata);
        horizontalPodAutoscaler.setSpec(spec);

        return horizontalPodAutoscaler;
    }

    @Override
    public String getLogWithNamespaceAndPod(@PathVariable("nameSpace") String namespace, @PathVariable("podName") String podName) {
       /* KubernetesClient client = K8sUtils.getKubernetesClient(masterUrl);
        // 校验该podName是否存在
        Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) {
            return "该pod不存在";
        }

        // 查询指定namespace和pod的日志信息
        String podLogs = client.pods().inNamespace(namespace).withName(podName).getLog();
        // 把换行符替换成页面的换行符
        return podLogs.replaceAll("\n", "<br>");*/
        return "日志为空";

    }

    @Override
    public void deleteService(@PathVariable("serviceName") String serviceName) {
        KubernetesClient client = K8sUtils.getKubernetesClient(masterUrl);
        try {
            client.extensions().deployments().inNamespace(K8sUtils.DEFAULT_NAMESPACE).withName(serviceName).delete();
        } catch (KubernetesClientException e) {
            LOGGER.info("delete deployment failed, serviceName={}", serviceName);
            e.printStackTrace();
        }
    }

    @Override
    public void startService(@RequestBody DeploymentVo deploymentVo) {
        LOGGER.info("start服务, 进入到k8s启动服务");
        KubernetesClient client = K8sUtils.getKubernetesClient(masterUrl);
        // 启动服务时，Service和HPA已经存在，只需创建Deployment
        createDeployment(client, deploymentVo);
    }

    @Override
    public List<ContainerVo> getAllContainers(@PathVariable("serviceName") String serviceName) {
        KubernetesClient client = K8sUtils.getKubernetesClient(masterUrl);
        // 获取所有pod
        List<Pod> pods = client.pods().inNamespace(K8sUtils.DEFAULT_NAMESPACE).list().getItems();
        // 获取所有RS
        List<ReplicaSet> replicaSets = client.extensions().replicaSets().inNamespace(K8sUtils.DEFAULT_NAMESPACE).list().getItems();
        String replicaSetName = serviceName;
        List<ContainerVo> containerVoList = new ArrayList<>();

        for (ReplicaSet replicaSet : replicaSets) {
            replicaSetName = replicaSet.getMetadata().getName();
            int index = replicaSetName.lastIndexOf("-");
            String svcName = replicaSetName.substring(0, index);
            if (svcName.equals(serviceName)) {
                break;
            }
        }

        for (Pod pod : pods) {
            String podName = pod.getMetadata().getName();
            if (podName.contains(replicaSetName)) {
                // 拿到的是这种格式的时间，2018-01-24T09:02:19Z
                String createTime = pod.getMetadata().getCreationTimestamp();
                createTime = createTime.replace("Z", "UTC");
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
                Date date = new Date();
                try {
                    date = format.parse(createTime);
                } catch (Exception e) {
                    LOGGER.info("获取指定服务的podName{}的pod异常{}", podName, e);
                }
                containerVoList.add(new ContainerVo(podName, pod.getMetadata().getUid(),
                        pod.getStatus().getHostIP(), pod.getStatus().getPodIP(), date));
            }
        }

        return containerVoList;
    }
}
