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
    private final long timeout;
    private final TimeUnit timeUnit;
    private final int nodes;
    private final String imageName;
    private final Map<String, String> ipMap; // Map<id, ip>
    private final AtomicInteger joinedNodes;
    private final String clusterName;

    private DefaultDockerClient.Builder dockerClientBuilder;

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
        this.clusterName = clusterName;
        this.nodes = nodes;
        this.ipMap = Collections.synchronizedMap(new LinkedHashMap<>(nodes));
        this.joinedNodes = new AtomicInteger(0);
        this.timeUnit = timeUnit;
        this.timeout = StringUtils.isNotBlank(ENV_DOCKER_TIMEOUT)
                ? Long.parseLong(ENV_DOCKER_TIMEOUT)
                : timeout;
        this.imageName = StringUtils.isBlank(ENV_DOCKER_IMAGE)
                ? StringUtils.isNotBlank(imageName) ? imageName : DEFAULT_DOCKER_IMAGE
                : ENV_DOCKER_IMAGE;

        try {
            this.dockerClient = Optional.ofNullable(dockerClientBuilder).orElse(DefaultDockerClient.fromEnv()).build();
        } catch (DockerCertificateException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void start() {
        if (nodes <= 0) {
            throw new IllegalStateException("Nodes count must be grater than 0");
        }
        logger.debug("Cluster '{}' is starting...", clusterName);

        CountDownLatch clusterStartLatch = new CountDownLatch(nodes);
        List<Thread> threads = IntStream.range(0, nodes).mapToObj(i -> new Thread(() -> {
            startNode(clusterName, clusterName + i, imageName);
            clusterStartLatch.countDown();
        })).collect(Collectors.toList());
        threads.forEach(Thread::start);

        try {
            if (!clusterStartLatch.await(timeout, timeUnit)) {
                threads.forEach(Thread::interrupt);
                stop(); // stop all started containers
                throw new IllegalStateException("The timeout period elapsed prior to Riak cluster start completion");
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        logger.info("Cluster '{}' is ready ({} node(s)).", clusterName, joinedNodes);
    }

    public void stop() {
        ipMap.keySet().forEach(c -> DockerRiakUtils.deleteNode(dockerClient, c));
        logger.info("Cluster '{}' is stopped ({} node(s)).", clusterName, ipMap.size());

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

                if (joinedNodes.addAndGet(1) == nodes) {
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
        this.dockerClientBuilder = dockerClientBuilder;
    }
}
