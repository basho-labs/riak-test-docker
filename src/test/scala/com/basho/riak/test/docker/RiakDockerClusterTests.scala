package com.basho.riak.test.docker

import org.junit.{ClassRule, Rule, Test}

import scala.concurrent.duration._

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class RiakDockerClusterTests {

  @Test
  def canCreate1NodeCluster() = {

  }

}

object RiakDockerClusterTests {

  @ClassRule
  def cluster = DockerRiakCluster(RiakCluster(nodes = 3, timeout = 2 minutes, image = "basho/riak-ts"))

}
