package com.basho.riak.test;

import com.google.common.base.Strings;
import com.gs.collections.impl.list.mutable.FastList;
import com.gs.collections.impl.set.mutable.UnifiedSet;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerCertificateException;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.PortBinding;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.exceptions.Exceptions;
import rx.schedulers.Schedulers;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * @author Jon Brisbin <jbrisbin@basho.com>
 */
public class DockerRiakCluster implements TestRule {

  private static final Logger log = LoggerFactory.getLogger(DockerRiakCluster.class);

  private final String name;
  private final int pbPort;
  private final int httpPort;
  private final String baseImage;
  private final List<String> volumes;
  private final int nodes;
  private final boolean reset;
  private final int timeout;

  private final DockerClient docker;

  private Observable<String> containerNames;

  private List<InetSocketAddress> protobufHosts = FastList.newList();
  private List<InetSocketAddress> httpHosts = FastList.newList();

  private DockerRiakCluster(String name,
                            int pbPort,
                            int httpPort,
                            String baseImage,
                            List<String> volumes,
                            int nodes,
                            boolean reset,
                            int timeout) {
    this.name = name;
    this.pbPort = pbPort;
    this.httpPort = httpPort;
    this.baseImage = baseImage;
    this.volumes = volumes;
    this.nodes = nodes;
    this.reset = reset;
    this.timeout = timeout;

    try {
      this.docker = DefaultDockerClient.fromEnv().build();
    } catch (DockerCertificateException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  public Observable<String> containerNames() {
    return containerNames;
  }

  public InetSocketAddress[] protobufHosts() {
    InetSocketAddress[] addrs = new InetSocketAddress[protobufHosts.size()];
    protobufHosts.toArray(addrs);
    return addrs;
  }

  public InetSocketAddress[] httpHosts() {
    InetSocketAddress[] addrs = new InetSocketAddress[httpHosts.size()];
    httpHosts.toArray(addrs);
    return addrs;
  }

  @Override
  public Statement apply(final Statement base, Description description) {
    return new DockerRiakClusterStatement(base, description);
  }

  public static Builder create() {
    return new Builder();
  }

  public static Builder create(String name) {
    return new Builder().name(name);
  }

  private final class DockerRiakClusterStatement extends Statement {
    private final Statement base;
    private final String baseName;

    private String cluster1;

    public DockerRiakClusterStatement(Statement base, Description description) {
      this.base = base;
      if (!Strings.isNullOrEmpty(name)) {
        this.baseName = name;
      } else {
        if (!Strings.isNullOrEmpty(description.getMethodName())) {
          this.baseName = description.getMethodName();
        } else {
          this.baseName = description.getTestClass().getSimpleName();
        }
      }
      containerNames = Observable.range(1, nodes).map(i -> baseName + i);
    }

    @Override
    public void evaluate() throws Throwable {
      start();
      try {
        base.evaluate();
      } finally {
        stop();
      }
    }

    protected void start() throws Exception {
      // Stop and remove running containers
      List<String> names = containerNames
          .observeOn(Schedulers.io())
          .map(name -> {
            ContainerInfo info = null;
            try {
              info = docker.inspectContainer(name);
              if (info.state().running()) {
                if (log.isDebugEnabled()) {
                  log.debug("Stopping existing container {}", name);
                }
                docker.killContainer(info.id());
              }
              if (log.isDebugEnabled()) {
                log.debug("Removing existing container {}", name);
              }
              docker.removeContainer(info.id());
            } catch (Exception ignored) {
            }
            return name;
          })
          .toList()
          .toBlocking()
          .toFuture()
          .get(timeout, TimeUnit.SECONDS);

      if (log.isDebugEnabled()) {
        log.debug("Cleaned {} existing containers", names);
      }

      // Create and start new containers
      containerNames
          .map(name -> {
            HostConfig.Builder hostConfig = HostConfig.builder().publishAllPorts(true);
            if (!name.endsWith("1")) {
              hostConfig.links(cluster1 + ":" + baseName + 1);
            }
            return ContainerConfig.builder()
                .hostname(name)
                .hostConfig(hostConfig.build())
                .env("CLUSTER_NAME=" + this.baseName)
                .image(baseImage)
                .volumes(UnifiedSet.newSet(volumes))
                .build();
          })
          .map(config -> {
            try {
              if (log.isDebugEnabled()) {
                log.debug("Creating new container {}", config);
              }
              return docker.createContainer(config, config.hostname());
            } catch (Exception e) {
              throw Exceptions.propagate(e);
            }
          })
          .forEach(container -> {
            try {
              if (log.isDebugEnabled()) {
                log.debug("Starting new container {}", container.id());
              }
              docker.startContainer(container.id());

              ContainerInfo info = docker.inspectContainer(container.id());
              if (info.name().endsWith("1")) {
                cluster1 = container.id();
              }
              String ip = info.networkSettings().ipAddress();
              String dockerHost = docker.getHost();
              {
                String[] ipParts = ip.split("\\.");
                String[] dockerHostParts = dockerHost.split("\\.");
                if (!ipParts[0].equals(dockerHostParts[0])) {
                  // Docker host is running on different IP than Docker containers (like Mac OS X)
                  // Use the Docker host IP rather than the container IPs
                  ip = dockerHost;
                }
              }
              Map<String, List<PortBinding>> ports = info.networkSettings().ports();
              // Get PB port
              PortBinding b = ports.get(pbPort + "/tcp").get(0);
              protobufHosts.add(new InetSocketAddress(ip, Integer.parseInt(b.hostPort())));
              // Get HTTP port
              b = ports.get(httpPort + "/tcp").get(0);
              httpHosts.add(new InetSocketAddress(ip, Integer.parseInt(b.hostPort())));

              // Wait for Riak to fully start
              String riakAdmin = docker.execCreate(container.id(), new String[]{"riak-admin", "wait-for-service", "riak_kv"});
              try (LogStream out = docker.execStart(riakAdmin)) {
                while (docker.execInspect(riakAdmin).running()) {
                  LockSupport.parkNanos(1000000);
                }
              }
            } catch (Exception e) {
              throw Exceptions.propagate(e);
            }
          });
    }

    protected void stop() throws Exception {
      protobufHosts.clear();
      httpHosts.clear();

      List<String> names = containerNames
          .observeOn(Schedulers.io())
          .map(name -> {
            try {
              if (log.isDebugEnabled()) {
                log.debug("Stopping test container {}", name);
              }
              docker.killContainer(name);
              if (reset) {
                if (log.isDebugEnabled()) {
                  log.debug("Removing test container {}", name);
                }
                docker.removeContainer(name);
              }
            } catch (Exception e) {
              throw Exceptions.propagate(e);
            }
            return name;
          })
          .toList()
          .toBlocking()
          .toFuture()
          .get(timeout, TimeUnit.SECONDS);

      if (log.isDebugEnabled()) {
        log.debug("Cleaned test containers {}", names);
      }
    }
  }

  public static final class Builder {
    private String name;
    private int pbPort = 8087;
    private int httpPort = 8098;
    private String baseImage = "basho/riak";
    private List<String> volumes = Collections.emptyList();
    private int nodes = 1;
    private boolean reset = true;
    private int timeout = 30;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder protobufPort(int pb) {
      this.pbPort = pb;
      return this;
    }

    public Builder httpPort(int httpPort) {
      this.httpPort = httpPort;
      return this;
    }

    public Builder baseImage(String baseImage) {
      this.baseImage = baseImage;
      return this;
    }

    public Builder volumes(List<String> volumes) {
      this.volumes = volumes;
      return this;
    }

    public Builder nodes(int nodes) {
      this.nodes = nodes;
      return this;
    }

    public Builder reset(boolean reset) {
      this.reset = reset;
      return this;
    }

    public Builder timeout(int timeout) {
      this.timeout = timeout;
      return this;
    }

    public DockerRiakCluster build() {
      return new DockerRiakCluster(name, pbPort, httpPort, baseImage, volumes, nodes, reset, timeout);
    }
  }

}
