package com.basho.riak.test.cluster;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
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

    private static final String DEFAULT_DOCKER_IMAGE = "basho/riak-ts";

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
        this(new ClusterProperties() {{
            setClusterName(clusterName);
            setNodes(nodes);
            setTimeout(timeout);
            setImageName(imageName);
            setTimeUnit(timeUnit);
        }});
    }

    /**
     * Creates new instance of DockerRiakCluster
     *
     * @param properties set of configurations for this cluster
     */
    public DockerRiakCluster(ClusterProperties properties) {
        this.properties = properties;
        this.ipMap = Collections.synchronizedMap(new LinkedHashMap<>(properties.getNodes()));
        this.joinedNodes = new AtomicInteger(0);

        if (StringUtils.isNotBlank(ENV_DOCKER_TIMEOUT)) {
            this.properties.setTimeout(Long.parseLong(ENV_DOCKER_TIMEOUT));
        }

        if (StringUtils.isNotBlank(ENV_DOCKER_IMAGE)) {
            this.properties.setImageName(ENV_DOCKER_IMAGE);
        } else if (StringUtils.isBlank(properties.getImageName())) {
            this.properties.setImageName(DEFAULT_DOCKER_IMAGE);
        }

        if (StringUtils.isBlank(properties.getClusterName())) {
            logger.info("Cluster name is not provided. Random UUID will be using instead.");
            this.properties.setClusterName(UUID.randomUUID().toString());
        }

        try {
            this.dockerClient = Optional.ofNullable(properties.getDockerClientBuilder())
                    .orElse(DefaultDockerClient.fromEnv()).build();
        } catch (DockerCertificateException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void start() {
        if (properties.getNodes() <= 0) {
            throw new IllegalStateException("Nodes count must be grater than 0");
        }
        pullDockerImage(properties.getImageName());

        logger.debug("Cluster '{}' is starting...", properties.getClusterName());

        CountDownLatch clusterStartLatch = new CountDownLatch(properties.getNodes());
        List<Thread> threads = IntStream.range(0, properties.getNodes()).mapToObj(i -> new Thread(() -> {
            startNode(properties.getClusterName(), properties.getClusterName() + "-" + i, properties.getImageName());
            clusterStartLatch.countDown();
        })).collect(Collectors.toList());
        threads.forEach(Thread::start);

        try {
            if (!clusterStartLatch.await(properties.getTimeout(), properties.getTimeUnit())) {
                threads.forEach(Thread::interrupt);
                stop(); // stop all started containers
                throw new IllegalStateException("The timeout period elapsed prior to Riak cluster start completion");
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        createBucketTypes(dockerClient, ipMap.keySet().iterator().next());
        logger.info("Cluster '{}' is ready ({} node(s)).", properties.getClusterName(), joinedNodes);
    }

    public void stop() {
        ipMap.keySet().forEach(c -> DockerRiakUtils.deleteNode(dockerClient, c));
        logger.info("Cluster '{}' is stopped ({} node(s)).", properties.getClusterName(), ipMap.size());

        ipMap.clear();
    }

    public Set<String> getIps() {
        return new HashSet<>(ipMap.values());
    }

    private void pullDockerImage(String name) {
        // add :latest suffix if no tag provided
        String taggedName = name.contains(":") ? name : name + ":latest";

        logger.debug("Verifying Docker image '{}'...", taggedName);
        try {
            if (dockerClient.listImages(DockerClient.ListImagesParam.byName(taggedName)).stream()
                    .noneMatch(i -> i.repoTags().contains(taggedName))) {

                // pull docker image if there is no such image locally
                logger.debug("Docker image '{}' will be pulled from DockerHub", taggedName);
                dockerClient.pull(properties.getImageName());
                logger.info("Docker image '{}' was pulled from DockerHub", taggedName);
            } else {
                logger.debug("Docker image '{}' is already present. Pulling skipped", taggedName);
            }
        } catch (DockerException | InterruptedException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void createBucketTypes(DockerClient dockerClient, String containerId) {
        properties.getBucketTypes().forEach((t, p) -> {
            String props = p.entrySet().stream()
                    .map(e -> String.format("\"%s\":\"%s\"", e.getKey(), e.getValue()))
                    .collect(Collectors.joining(","));
            DockerRiakUtils.createBuketType(dockerClient, containerId, t, "{" + props + "}");
        });
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

                if (joinedNodes.addAndGet(1) == properties.getNodes()) {
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
        this.properties.setDockerClientBuilder(dockerClientBuilder);
    }

    public static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("unused")
    public static final class Builder {

        private final ClusterProperties properties;

        public Builder() {
            this.properties = new ClusterProperties();
        }

        public Builder(Builder builder) {
            this.properties = new ClusterProperties(builder.properties);
        }

        public Builder withTimeout(long timeout) {
            properties.setTimeout(timeout);
            return this;
        }

        public Builder withTimeUnit(TimeUnit timeUnit) {
            properties.setTimeUnit(timeUnit);
            return this;
        }

        public Builder withNodes(int nodes) {
            properties.setNodes(nodes);
            return this;
        }

        public Builder withImageName(String imageName) {
            properties.setImageName(imageName);
            return this;
        }

        public Builder withClusterName(String clusterName) {
            properties.setClusterName(clusterName);
            return this;
        }

        public Builder withDockerClientBuilder(DefaultDockerClient.Builder builder) {
            properties.setDockerClientBuilder(builder);
            return this;
        }

        public Builder withBucketType(String bucketType, Map<String, String> props) {
            this.properties.getBucketTypes().put(bucketType, props);
            return this;
        }

        public DockerRiakCluster build() {
            return new DockerRiakCluster(properties);
        }
    }
}