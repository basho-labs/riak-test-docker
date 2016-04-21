/*
 * Copyright 2016 by the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.basho.riak.test;

import com.basho.riak.client.core.RiakCluster;
import com.basho.riak.client.core.RiakNode;
import com.basho.riak.client.core.operations.ListBucketsOperation;
import com.basho.riak.client.core.operations.StoreOperation;
import com.basho.riak.client.core.query.Namespace;
import com.basho.riak.client.core.query.RiakObject;
import com.basho.riak.client.core.util.BinaryValue;
import com.basho.riak.client.core.util.HostAndPort;
import com.gs.collections.impl.list.mutable.FastList;
import org.junit.*;
import org.junit.runners.MethodSorters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

/**
 * @author Jon Brisbin <jbrisbin@basho.com>
 */
@FixMethodOrder(MethodSorters.JVM)
public class DockerRiakClusterClassRuleTests {

  private static final Logger LOG = LoggerFactory.getLogger(DockerRiakClusterClassRuleTests.class);

  @ClassRule
  public static DockerRiakCluster cluster = DockerRiakCluster.create()
      .nodes(3)
      .baseImage("basho/riak-ts")
      .build();

  private static RiakCluster riak;

  @BeforeClass
  public static void setup() {
    List<RiakNode> nodes = FastList.newListWith(cluster.protobufHosts())
        .collect(addr -> new RiakNode.Builder()
            .withRemoteAddress(HostAndPort.fromParts(addr.getHostName(), addr.getPort()))
            .build());
    riak = RiakCluster.builder(nodes).withExecutionAttempts(3).build();
    riak.start();
  }

  @AfterClass
  public static void cleanup() throws Exception {
    riak.shutdown().get();
  }

  @Test
  public void startsWithNoBuckets() throws Exception {
    ListBucketsOperation.Response resp = riak.execute(
        new ListBucketsOperation.Builder().build()
    ).get();

    assertThat("Bucket list is empty", resp.getBuckets().size(), is(0));
  }

  @Test
  public void createsAnEntry() throws Exception {
    Namespace test = new Namespace("test");
    RiakObject content = new RiakObject();
    content.setContentType("text/plain");
    content.setValue(BinaryValue.create("Hello World!"));
    StoreOperation.Response resp = riak.execute(
        new StoreOperation.Builder(test)
            .withContent(content)
            .build()
    ).get();

    assertThat("Entry was created", resp.getGeneratedKey(), notNullValue());
  }

  @Test
  public void nowHasData() throws Exception {
    ListBucketsOperation.Response resp = riak.execute(
        new ListBucketsOperation.Builder().build()
    ).get();

    assertThat("Bucket list is NOT empty", resp.getBuckets().size(), is(1));
  }

}
