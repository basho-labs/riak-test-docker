package com.basho.riak.test.cluster;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.messages.NetworkSettings;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DockerRiakCluster {

    private static final Logger logger = LoggerFactory.getLogger(DockerRiakCluster.class);

    private static final String DEFAULT_DOCKER_IMAGE = "basho/riak-ts:1.3.0";

    private static final String ENV_DOCKER_IMAGE = System.getProperty("com.basho.riak.test.cluster.image-name");
    private static final String ENV_DOCKER_TIMEOUT = System.getProperty("com.basho.riak.test.cluster.timeout");

    private final DockerClient dockerClient;
    private final Map<String, String> ipMap; // Map<id, ip>
    private final AtomicInteger joinedNodes;
    private final ClusterProperties properties;

    /**
     * Creates new instance of DockerRiakCluster.
     *
     * @param nodes   a number of cluster nodes
     * @param timeout cluster startup timeout in <b>minutes</b>
     */
    public DockerRiakCluster(String clusterName, int nodes, long timeout) {
        this(clusterName, nodes, timeout, TimeUnit.MINUTES);
    }

    /**
     * Creates new instance of DockerRiakCluster
     *
     * @param nodes    a number of cluster nodes
     * @param timeout  cluster startup timeout
     * @param timeUnit unit of granularity
     */
    public DockerRiakCluster(String clusterName, int nodes, long timeout, TimeUnit timeUnit) {
        this(clusterName, nodes, null, timeout, timeUnit);
    }

    /**
     * Creates new instance of DockerRiakCluster
     *
     * @param clusterName a name of this cluster
     * @param nodes       a number of cluster nodes
     * @param imageName   name of Docker image for building cluster
     * @param timeout     cluster startup timeout
     * @param timeUnit    unit of granularity
     */
    public DockerRiakCluster(String clusterName, int nodes, String imageName, long timeout, TimeUnit timeUnit) {
        this.ipMap = Collections.synchronizedMap(new LinkedHashMap<>(nodes));
        this.joinedNodes = new AtomicInteger(0);
        this.properties = new ClusterProperties();

        this.properties.clusterName = clusterName;
        this.properties.nodes = nodes;
        this.properties.timeUnit = timeUnit;
        this.properties.timeout = StringUtils.isNotBlank(ENV_DOCKER_TIMEOUT)
                ? Long.parseLong(ENV_DOCKER_TIMEOUT)
                : timeout;
        this.properties.imageName = StringUtils.isBlank(ENV_DOCKER_IMAGE)
                ? StringUtils.isNotBlank(imageName) ? imageName : DEFAULT_DOCKER_IMAGE
                : ENV_DOCKER_IMAGE;

        try {
            this.dockerClient = Optional.ofNullable(properties.dockerClientBuilder)
                    .orElse(DefaultDockerClient.fromEnv()).build();
        } catch (DockerCertificateException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void start() {
        if (properties.nodes <= 0) {
            throw new IllegalStateException("Nodes count must be grater than 0");
        }
        logger.debug("Cluster '{}' is starting...", properties.clusterName);

        CountDownLatch clusterStartLatch = new CountDownLatch(properties.nodes);
        List<Thread> threads = IntStream.range(0, properties.nodes).mapToObj(i -> new Thread(() -> {
            startNode(properties.clusterName, properties.clusterName + i, properties.imageName);
            clusterStartLatch.countDown();
        })).collect(Collectors.toList());
        threads.forEach(Thread::start);

        try {
            if (!clusterStartLatch.await(properties.timeout, properties.timeUnit)) {
                threads.forEach(Thread::interrupt);
                stop(); // stop all started containers
                throw new IllegalStateException("The timeout period elapsed prior to Riak cluster start completion");
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        logger.info("Cluster '{}' is ready ({} node(s)).", properties.clusterName, joinedNodes);
    }

    public void stop() {
        ipMap.keySet().forEach(c -> DockerRiakUtils.deleteNode(dockerClient, c));
        logger.info("Cluster '{}' is stopped ({} node(s)).", properties.clusterName, ipMap.size());

        ipMap.clear();
    }

    public Set<String> getIps() {
        return new HashSet<>(ipMap.values());
    }

    private void startNode(String clusterName, String nodeName, String imageName) {
        try {
            String containerId = DockerRiakUtils.startNode(dockerClient, nodeName, clusterName, imageName);
            NetworkSettings networkSettings = dockerClient.inspectContainer(containerId).networkSettings();

            ipMap.put(containerId, networkSettings.ipAddress());

            logger.info("Node '{}({})' is up and running.", nodeName, networkSettings.ipAddress());
            if (!joinedNodes.compareAndSet(0, 1)) {
                // it's not the first node we must join to first one
                logger.debug("Node '{}({})' is going to be joined to cluster...", nodeName, networkSettings.ipAddress());
                while (ipMap.isEmpty()) {
                    TimeUnit.SECONDS.sleep(1);
                }
                String ip = ipMap.values().iterator().next();
                logger.debug("Node '{}({})' is joining to '{}'...", nodeName, networkSettings.ipAddress(), ip);
                DockerRiakUtils.joinNode(dockerClient, containerId, ip);
                logger.info("Node '{}({})' was joined to '{}'", nodeName, networkSettings.ipAddress(), ip);

                if (joinedNodes.addAndGet(1) == properties.nodes) {
                    // all Riak nodes are joined and we need to wait until ring is ready
                    logger.debug("Cluster plan is ready. {} nodes joined. Applying changes...", joinedNodes.get());
                    DockerRiakUtils.commitClusterPlan(dockerClient, containerId);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unused")
    public void setDockerClientBuilder(DefaultDockerClient.Builder dockerClientBuilder) {
        this.properties.dockerClientBuilder = dockerClientBuilder;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static class ClusterProperties {
        private long timeout;
        private TimeUnit timeUnit = TimeUnit.MINUTES;
        private int nodes;
        private String imageName;
        private String clusterName;
        private DefaultDockerClient.Builder dockerClientBuilder;
    }

    @SuppressWarnings("unused")
    public static final class Builder {

        private ClusterProperties properties = new ClusterProperties();

        public Builder withTimeout(long timeout) {
            this.properties.timeout = timeout;
            return this;
        }

        public Builder withTimeUnit(TimeUnit timeUnit) {
            this.properties.timeUnit = timeUnit;
            return this;
        }

        public Builder withNodes(int nodes) {
            this.properties.nodes = nodes;
            return this;
        }

        public Builder withImageName(String imageName) {
            this.properties.imageName = imageName;
            return this;
        }

        public Builder withClusterName(String clusterName) {
            this.properties.clusterName = clusterName;
            return this;
        }

        public Builder withDockerClientBuilder(DefaultDockerClient.Builder builder) {
            this.properties.dockerClientBuilder = builder;
            return this;
        }

        public DockerRiakCluster build() {
            return new DockerRiakCluster(
                    properties.clusterName,
                    properties.nodes,
                    properties.imageName,
                    properties.timeout,
                    properties.timeUnit);
        }
    }
}
