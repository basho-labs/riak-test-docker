package com.basho.riak.test.docker

import org.junit.{Rule, Test}

import scala.concurrent.duration._

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class RiakDockerClusterTests {

  @Rule
  def cluster = RiakDockerCluster(RiakCluster(nodes = 3, timeout = 2 minutes, image = "basho/riak-ts"))

  @Test
  def canCreate1NodeCluster() = {

  }

}
