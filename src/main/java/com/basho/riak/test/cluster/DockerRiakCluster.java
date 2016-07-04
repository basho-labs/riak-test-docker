package com.basho.riak.test.cluster;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.NetworkSettings;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DockerRiakCluster {

    private static final Logger logger = LoggerFactory.getLogger(DockerRiakCluster.class);

    private static final String OS = System.getProperty("os.name").toLowerCase();
    public static final String DEFAULT_DOCKER_IMAGE = "basho/riak-ts";

    private static final String ENV_DOCKER_IMAGE = System.getProperty("com.basho.riak.test.cluster.image-name");
    private static final String ENV_DOCKER_TIMEOUT = System.getProperty("com.basho.riak.test.cluster.timeout");

    private final DockerClient dockerClient;
    private final Map<String, String> ipMap; // Map<id, ip>
    private final AtomicInteger joinedNodes;
    private final String clusterName;
    private final ClusterProperties properties;

    private boolean started = false;

    /**
     * Creates new instance of DockerRiakCluster.
     *
     * @param nodes   a number of cluster nodes
     * @param timeout cluster startup timeout in <b>minutes</b>
     */
    public DockerRiakCluster(int nodes, long timeout) {
        this(nodes, timeout, TimeUnit.MINUTES);
    }

    /**
     * Creates new instance of DockerRiakCluster
     *
     * @param nodes    a number of cluster nodes
     * @param timeout  cluster startup timeout
     * @param timeUnit unit of granularity
     */
    public DockerRiakCluster(int nodes, long timeout, TimeUnit timeUnit) {
        this(nodes, null, timeout, timeUnit);
    }

    /**
     * Creates new instance of DockerRiakCluster
     *
     * @param nodes     a number of cluster nodes
     * @param imageName name of Docker image for building cluster
     * @param timeout   cluster startup timeout
     * @param timeUnit  unit of granularity
     */
    public DockerRiakCluster(int nodes, String imageName, long timeout, TimeUnit timeUnit) {
        this(new ClusterProperties() {{
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
        this.clusterName = UUID.randomUUID().toString();

        if (StringUtils.isNotBlank(ENV_DOCKER_TIMEOUT)) {
            this.properties.setTimeout(Long.parseLong(ENV_DOCKER_TIMEOUT));
            this.properties.setTimeUnit(TimeUnit.MINUTES);
        }

        if (StringUtils.isNotBlank(ENV_DOCKER_IMAGE)) {
            this.properties.setImageName(ENV_DOCKER_IMAGE);
        } else if (StringUtils.isBlank(properties.getImageName())) {
            this.properties.setImageName(DEFAULT_DOCKER_IMAGE);
        }

        this.dockerClient = Optional.ofNullable(properties.getDockerClientBuilder())
                .orElseGet(() -> {
                    if ((OS.contains("win") || OS.contains("mac"))
                            && (!System.getenv().containsKey("DOCKER_HOST") || !System.getenv().containsKey("DOCKER_CERT_PATH"))) {
                        String command = OS.contains("win") ? "set" : "export";
                        throw new IllegalStateException(
                                "\n==================================================================================\n" +
                                        "\tDocker is not configured properly. Environment variables must be configured:\n\t\t" +
                                        command + " DOCKER_HOST=\"tcp://<Docker machine ip>:2376\"\n\t\t" +
                                        command + " DOCKER_CERT_PATH=<path to Docker cert>\n\n\t" +
                                        "Or just use 'docker-machine env' command if you are using docker-machine utility." +
                                        "\n==================================================================================\n");

                    }

                    try {
                        return DefaultDockerClient.fromEnv();
                    } catch (DockerCertificateException e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }).build();

        Runtime.getRuntime().addShutdownHook(new Thread(DockerRiakCluster.this::stop));
    }

    public void start() {
        if (properties.getNodes() <= 0) {
            throw new IllegalStateException("Nodes count must be grater than 0");
        }
        pullDockerImage(properties.getImageName());

        logger.debug("Cluster '{}' is starting...", clusterName);
        started = true;

        CountDownLatch clusterStartLatch = new CountDownLatch(properties.getNodes());
        List<Thread> threads = IntStream.range(0, properties.getNodes()).mapToObj(i -> new Thread(() -> {
            startNode(clusterName, clusterName + "-" + i, properties.getImageName());
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
        checkClusterAccess();
        logger.info("Cluster '{}' is ready ({} node(s)).", clusterName, joinedNodes);
    }

    public void stop() {
        if (!started) {
            return;
        }
        DockerRiakUtils.removeCluster(dockerClient, clusterName);
        ipMap.clear();
        Optional.ofNullable(dockerClient).ifPresent(DockerClient::close);
        started = false;
        logger.info("Cluster '{}' is stopped.", clusterName);
    }

    public Set<String> getIps() {
        return new HashSet<>(ipMap.values());
    }

    private void pullDockerImage(String name) {
        // add :latest suffix if no tag provided
        String taggedName = name.contains(":") ? name : name + ":latest";

        logger.debug("Checking Docker image '{}'...", taggedName);
        try {
            // pull docker image if there is no such image locally
            logger.debug("Docker image '{}' will be synchronized with DockerHub", taggedName);
            dockerClient.pull(properties.getImageName());
            logger.info("Docker image '{}' was synchronized with DockerHub", taggedName);
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

    private void checkClusterAccess() {
        if (getIps().isEmpty()) {
            logger.warn("Cluster contains 0 nodes");
            return;
        }

        logger.debug("Checking cluster access...");
        String host = getIps().iterator().next();
        try {
            if ((OS.contains("win") || OS.contains("mac")) && !InetAddress.getByName(host).isReachable(1000)) {
                stop(); // stop cluster if it's unreachable
                String command = String.format(OS.contains("win")
                        ? "route ADD 172.17.0.0 MASK 255.255.0.0 %s"
                        : "sudo route -n add 172.17.0.0/16 %s", dockerClient.getHost());
                throw new IllegalStateException(String.format(
                        "\n==================================================================================\n" +
                                "\tDockerized Riak cluster is unreachable. Static route must be added:\n\t\t%s" +
                                "\n==================================================================================\n",
                        command));
            }
        } catch (IOException e) {
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