/*
 * Copyright 2018 Netflix, Inc.
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

package com.netflix.titus.runtime.connector.agent;

import com.google.inject.AbstractModule;
import com.netflix.titus.api.agent.service.ReadOnlyAgentOperations;
import com.netflix.titus.runtime.connector.agent.client.GrpcAgentManagementClient;
import com.netflix.titus.runtime.connector.agent.replicator.AgentDataReplicatorProvider;

public class AgentManagerConnectorModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(AgentManagementClient.class).to(GrpcAgentManagementClient.class);

        bind(AgentDataReplicator.class).toProvider(AgentDataReplicatorProvider.class);
        bind(ReadOnlyAgentOperations.class).to(CachedReadOnlyAgentOperations.class);
    }
}
