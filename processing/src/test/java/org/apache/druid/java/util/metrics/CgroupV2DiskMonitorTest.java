/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.java.util.metrics;

import org.apache.druid.java.util.metrics.cgroups.CgroupDiscoverer;
import org.apache.druid.java.util.metrics.cgroups.ProcCgroupV2Discoverer;
import org.apache.druid.java.util.metrics.cgroups.TestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class CgroupV2DiskMonitorTest
{
  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();
  private File procDir;
  private File cgroupDir;
  private File statFile;
  private CgroupDiscoverer discoverer;

  @Before
  public void setUp() throws IOException
  {
    cgroupDir = temporaryFolder.newFolder();
    procDir = temporaryFolder.newFolder();
    discoverer = new ProcCgroupV2Discoverer(procDir.toPath());
    TestUtils.setUpCgroupsV2(procDir, cgroupDir);

    statFile = new File(cgroupDir, "io.stat");
    TestUtils.copyOrReplaceResource("/cgroupv2/io.stat", statFile);
  }

  @Test
  public void testMonitor() throws IOException
  {
    final CgroupV2DiskMonitor monitor = new CgroupV2DiskMonitor(discoverer);
    final StubServiceEmitter emitter = new StubServiceEmitter("service", "host");
    Assert.assertTrue(monitor.doMonitor(emitter));
    Assert.assertEquals(0, emitter.getNumEmittedEvents());

    emitter.flush();

    TestUtils.copyOrReplaceResource("/cgroupv2/io.stat-2", statFile);

    Assert.assertTrue(monitor.doMonitor(emitter));
    Assert.assertEquals(4, emitter.getNumEmittedEvents());
    Assert.assertTrue(
        emitter
            .getEvents()
            .stream()
            .map(e -> e.toMap().get("value"))
            .allMatch(val -> Long.valueOf(10).equals(val)));
  }
}
