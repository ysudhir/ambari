/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.api.services;


import org.apache.ambari.server.api.resources.ResourceDefinition;
import org.apache.ambari.server.controller.internal.RequestStatusImpl;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.api.query.Query;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.*;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

import static org.easymock.EasyMock.*;

/**
 * Unit tests for UpdatePersistenceManager.
 */
public class UpdatePersistenceManagerTest {
  @Test
  public void testPersist() throws Exception {
    ResourceDefinition resource = createMock(ResourceDefinition.class);
    ClusterController controller = createMock(ClusterController.class);
    Schema schema = createMock(Schema.class);
    Request serverRequest = createStrictMock(Request.class);
    Query query = createMock(Query.class);
    Predicate predicate = createMock(Predicate.class);

    Set<Map<PropertyId, Object>> setProperties = new HashSet<Map<PropertyId, Object>>();
    Map<PropertyId, Object> mapProperties = new HashMap<PropertyId, Object>();
    mapProperties.put(PropertyHelper.getPropertyId("bar", "foo"), "value");
    setProperties.add(mapProperties);

    //expectations
    expect(resource.getType()).andReturn(Resource.Type.Component);
    expect(resource.getQuery()).andReturn(query);
    expect(query.getInternalPredicate()).andReturn(predicate);

    expect(controller.updateResources(Resource.Type.Component, serverRequest, predicate)).andReturn(new RequestStatusImpl(null));

    replay(resource, controller, schema, serverRequest, query, predicate);

    new TestUpdatePersistenceManager(controller, setProperties, serverRequest).persist(resource, setProperties);

    verify(resource, controller, schema, serverRequest, query, predicate);
  }

  private class TestUpdatePersistenceManager extends UpdatePersistenceManager {

    private ClusterController m_controller;
    private Request m_request;
    private Set<Map<PropertyId, Object>> m_setProperties;

    private TestUpdatePersistenceManager(ClusterController controller,
                                         Set<Map<PropertyId, Object>> setProperties,
                                         Request controllerRequest) {
      m_controller = controller;
      m_setProperties = setProperties;
      m_request = controllerRequest;
    }

    @Override
    protected ClusterController getClusterController() {
      return m_controller;
    }

    @Override
    protected Request createControllerRequest(Set<Map<PropertyId, Object>> setProperties) {
      assertEquals(1, setProperties.size());
      assertEquals(m_setProperties, setProperties);
      return m_request;
    }
  }

}
