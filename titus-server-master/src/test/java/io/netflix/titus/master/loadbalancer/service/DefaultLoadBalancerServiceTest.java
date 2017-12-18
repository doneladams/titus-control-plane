/*
 * Copyright 2017 Netflix, Inc.
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

package io.netflix.titus.master.loadbalancer.service;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.netflix.spectator.api.NoopRegistry;
import io.netflix.titus.api.connector.cloud.LoadBalancerConnector;
import io.netflix.titus.api.jobmanager.model.job.ServiceJobTask;
import io.netflix.titus.api.jobmanager.model.job.Task;
import io.netflix.titus.api.jobmanager.model.job.TaskState;
import io.netflix.titus.api.jobmanager.model.job.TaskStatus;
import io.netflix.titus.api.jobmanager.model.job.event.JobManagerEvent;
import io.netflix.titus.api.jobmanager.model.job.event.TaskUpdateEvent;
import io.netflix.titus.api.jobmanager.service.JobManagerException;
import io.netflix.titus.api.jobmanager.service.V3JobOperations;
import io.netflix.titus.api.loadbalancer.model.JobLoadBalancer;
import io.netflix.titus.api.loadbalancer.model.LoadBalancerState;
import io.netflix.titus.api.loadbalancer.model.LoadBalancerTarget;
import io.netflix.titus.api.loadbalancer.model.sanitizer.DefaultLoadBalancerJobValidator;
import io.netflix.titus.api.loadbalancer.model.sanitizer.LoadBalancerJobValidator;
import io.netflix.titus.api.loadbalancer.model.sanitizer.LoadBalancerValidationConfiguration;
import io.netflix.titus.api.loadbalancer.store.LoadBalancerStore;
import io.netflix.titus.common.runtime.TitusRuntime;
import io.netflix.titus.common.runtime.internal.DefaultTitusRuntime;
import io.netflix.titus.common.util.CollectionsExt;
import io.netflix.titus.runtime.endpoint.v3.grpc.TaskAttributes;
import io.netflix.titus.runtime.store.v3.memory.InMemoryLoadBalancerStore;
import org.junit.Before;
import org.junit.Test;
import rx.Completable;
import rx.observers.AssertableSubscriber;
import rx.schedulers.Schedulers;
import rx.schedulers.TestScheduler;
import rx.subjects.PublishSubject;

import static io.netflix.titus.master.loadbalancer.service.LoadBalancerTests.applyValidGetJobMock;
import static io.netflix.titus.master.loadbalancer.service.LoadBalancerTests.count;
import static io.netflix.titus.master.loadbalancer.service.LoadBalancerTests.mockConfiguration;
import static io.netflix.titus.master.loadbalancer.service.LoadBalancerTests.mockValidationConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DefaultLoadBalancerServiceTest {

    private TitusRuntime runtime;
    private LoadBalancerConnector client;
    private V3JobOperations jobOperations;
    private LoadBalancerStore loadBalancerStore;
    private LoadBalancerJobValidator validator;
    private TargetTracking targetTracking;
    private TestScheduler testScheduler;

    private void defaultStubs() {
        when(client.registerAll(any(), any())).thenReturn(Completable.complete());
        when(client.deregisterAll(any(), any())).thenReturn(Completable.complete());
        when(jobOperations.observeJobs()).thenReturn(PublishSubject.create());
    }

    @Before
    public void setUp() throws Exception {
        runtime = new DefaultTitusRuntime(new NoopRegistry());
        client = mock(LoadBalancerConnector.class);
        loadBalancerStore = new InMemoryLoadBalancerStore();
        jobOperations = mock(V3JobOperations.class);
        LoadBalancerValidationConfiguration validationConfiguration = mockValidationConfig(30);
        validator = new DefaultLoadBalancerJobValidator(jobOperations, loadBalancerStore, validationConfiguration);
        targetTracking = new TargetTracking();
        testScheduler = Schedulers.test();
    }

    @Test
    public void addLoadBalancerRegistersTasks() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();

        defaultStubs();
        applyValidGetJobMock(jobOperations, jobId);
        when(jobOperations.getTasks(jobId)).thenReturn(CollectionsExt.merge(
                LoadBalancerTests.buildTasksStarted(5, jobId),
                LoadBalancerTests.buildTasks(2, jobId, TaskState.StartInitiated),
                LoadBalancerTests.buildTasks(2, jobId, TaskState.KillInitiated),
                LoadBalancerTests.buildTasks(3, jobId, TaskState.Finished),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.Disconnected)
        ));

        LoadBalancerConfiguration configuration = mockConfiguration(5, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(runtime, configuration, client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        assertTrue(service.addLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(service.getJobLoadBalancers(jobId).toBlocking().first()).isEqualTo(loadBalancerId);
        verify(jobOperations).getTasks(jobId);

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client).registerAll(eq(loadBalancerId), argThat(targets -> targets != null && targets.size() == 5));
        verify(client, never()).deregisterAll(eq(loadBalancerId), any());
    }

    @Test
    public void targetsAreBufferedUpToATimeout() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();

        defaultStubs();
        applyValidGetJobMock(jobOperations, jobId);
        when(jobOperations.getTasks(jobId)).thenReturn(CollectionsExt.merge(
                LoadBalancerTests.buildTasksStarted(3, jobId),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.StartInitiated),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.KillInitiated),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.Finished),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.Disconnected)
        ));

        LoadBalancerConfiguration configuration = mockConfiguration(1000, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(runtime, configuration,
                client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();
        assertTrue(service.addLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(service.getJobLoadBalancers(jobId).toBlocking().first()).isEqualTo(loadBalancerId);
        verify(jobOperations).getTasks(jobId);

        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).registerAll(any(), any());
        verify(client, never()).deregisterAll(any(), any());

        testScheduler.advanceTimeBy(5_001, TimeUnit.MILLISECONDS);

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client).registerAll(eq(loadBalancerId), argThat(targets -> targets != null && targets.size() == 3));
        verify(client, never()).deregisterAll(eq(loadBalancerId), any());
    }

    @Test
    public void emptyBatchesAreFilteredOut() throws Exception {
        defaultStubs();

        LoadBalancerConfiguration configuration = mockConfiguration(1000, 5_000);
        LoadBalancerValidationConfiguration validationConfiguration = mockValidationConfig(30);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(runtime, configuration,
                client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).registerAll(any(), any());
        verify(client, never()).deregisterAll(any(), any());

        testScheduler.advanceTimeBy(5_001, TimeUnit.MILLISECONDS);

        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).registerAll(any(), any());
        verify(client, never()).deregisterAll(any(), any());
    }

    @Test
    public void addSkipLoadBalancerOperationsOnErrors() throws Exception {
        final String firstJobId = UUID.randomUUID().toString();
        final String secondJobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();

        defaultStubs();
        // first fails, second succeeds
        applyValidGetJobMock(jobOperations, firstJobId).thenThrow(new RuntimeException());
        applyValidGetJobMock(jobOperations, secondJobId);
        when(jobOperations.getTasks(secondJobId)).thenReturn(LoadBalancerTests.buildTasksStarted(2, secondJobId));

        LoadBalancerConfiguration configuration = mockConfiguration(2, 5_000);
        LoadBalancerValidationConfiguration validationConfiguration = mockValidationConfig(30);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(
                runtime, configuration, client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        // first fails and gets skipped after being saved, so convergence can pick it up later
        assertTrue(service.addLoadBalancer(firstJobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(service.getJobLoadBalancers(firstJobId).toBlocking().first()).isEqualTo(loadBalancerId);

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertNoValues();
        verify(jobOperations, never()).getTasks(firstJobId);
        verify(client, never()).registerAll(any(), any());
        verify(client, never()).deregisterAll(any(), any());

        // second succeeds
        assertTrue(service.addLoadBalancer(secondJobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(service.getJobLoadBalancers(secondJobId).toBlocking().first()).isEqualTo(loadBalancerId);

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(jobOperations).getTasks(secondJobId);
        verify(client).registerAll(eq(loadBalancerId), argThat(targets -> targets != null && targets.size() == 2));
        verify(client, never()).deregisterAll(eq(loadBalancerId), any());
    }

    @Test
    public void multipleLoadBalancersPerJob() throws Exception {
        final PublishSubject<JobManagerEvent<?>> taskEvents = PublishSubject.create();
        final String jobId = UUID.randomUUID().toString();
        final String firstLoadBalancerId = "lb-" + UUID.randomUUID().toString();
        final String secondLoadBalancerId = "lb-" + UUID.randomUUID().toString();
        final int numberOfStartedTasks = 5;

        when(client.registerAll(any(), any())).thenReturn(Completable.complete());
        when(client.deregisterAll(any(), any())).thenReturn(Completable.complete());
        when(jobOperations.observeJobs()).thenReturn(taskEvents);
        applyValidGetJobMock(jobOperations, jobId);
        when(jobOperations.getTasks(jobId)).thenReturn(CollectionsExt.merge(
                LoadBalancerTests.buildTasksStarted(numberOfStartedTasks, jobId),
                LoadBalancerTests.buildTasks(2, jobId, TaskState.StartInitiated),
                LoadBalancerTests.buildTasks(2, jobId, TaskState.KillInitiated),
                LoadBalancerTests.buildTasks(3, jobId, TaskState.Finished),
                LoadBalancerTests.buildTasks(1, jobId, TaskState.Disconnected)
        ));

        final int batchSize = 2 * numberOfStartedTasks; // expect 1 operation per <loadBalancer, task> pair
        LoadBalancerConfiguration configuration = mockConfiguration(batchSize, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(
                runtime, configuration, client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        // associate two load balancers to the same job

        assertTrue(service.addLoadBalancer(jobId, firstLoadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertTrue(service.addLoadBalancer(jobId, secondLoadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(service.getJobLoadBalancers(jobId).toList().toBlocking().single())
                .containsOnly(firstLoadBalancerId, secondLoadBalancerId);
        verify(jobOperations, times(2)).getTasks(jobId);

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client).registerAll(eq(firstLoadBalancerId), argThat(targets -> targets != null && targets.size() == numberOfStartedTasks));
        verify(client).registerAll(eq(secondLoadBalancerId), argThat(targets -> targets != null && targets.size() == numberOfStartedTasks));
        verify(client, never()).deregisterAll(eq(firstLoadBalancerId), any());
        verify(client, never()).deregisterAll(eq(secondLoadBalancerId), any());

        // now some more tasks are added to the job, check if both load balancers get updated

        for (int i = 0; i < numberOfStartedTasks; i++) {
            final String taskId = UUID.randomUUID().toString();
            final Task startingWithIp = ServiceJobTask.newBuilder()
                    .withJobId(jobId)
                    .withId(taskId)
                    .withStatus(TaskStatus.newBuilder().withState(TaskState.StartInitiated).build())
                    .withTaskContext(CollectionsExt.asMap(
                            TaskAttributes.TASK_ATTRIBUTES_CONTAINER_IP, String.format("%1$d.%1$d.%1$d.%1$d", i + 1)
                    )).build();
            final Task started = startingWithIp.toBuilder()
                    .withStatus(TaskStatus.newBuilder().withState(TaskState.Started).build())
                    .build();

            taskEvents.onNext(TaskUpdateEvent.taskChange(null, started, startingWithIp));
        }
        testScheduler.triggerActions();
        testSubscriber.assertNoErrors().assertValueCount(2);
        verify(client, times(2)).registerAll(eq(firstLoadBalancerId), argThat(targets -> targets != null && targets.size() == numberOfStartedTasks));
        verify(client, times(2)).registerAll(eq(secondLoadBalancerId), argThat(targets -> targets != null && targets.size() == numberOfStartedTasks));
        verify(client, never()).deregisterAll(eq(firstLoadBalancerId), any());
        verify(client, never()).deregisterAll(eq(secondLoadBalancerId), any());
    }

    @Test
    public void targetsAreBufferedInBatches() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final int batchSize = random.nextInt(5, 20);
        final int extra = random.nextInt(1, batchSize);

        defaultStubs();
        applyValidGetJobMock(jobOperations, jobId);
        when(jobOperations.getTasks(jobId)).thenReturn(LoadBalancerTests.buildTasksStarted(batchSize + extra, jobId));

        LoadBalancerConfiguration configuration = mockConfiguration(batchSize, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(
                runtime, configuration, client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        assertTrue(service.addLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(service.getJobLoadBalancers(jobId).toBlocking().first()).isEqualTo(loadBalancerId);

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client).registerAll(eq(loadBalancerId), argThat(targets -> targets != null && targets.size() == batchSize));
        verify(client, never()).deregisterAll(eq(loadBalancerId), any());
    }

    @Test
    public void batchesWithErrorsAreSkipped() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final int batchSize = random.nextInt(3, 10);
        final int extra = random.nextInt(1, batchSize);

        when(client.registerAll(any(), any())).thenReturn(Completable.error(new RuntimeException()))
                .thenReturn(Completable.complete());
        when(client.deregisterAll(any(), any())).thenReturn(Completable.complete());
        when(jobOperations.observeJobs()).thenReturn(PublishSubject.create());
        applyValidGetJobMock(jobOperations, jobId);
        // 2 batches
        when(jobOperations.getTasks(jobId)).thenReturn(LoadBalancerTests.buildTasksStarted(2 * batchSize + extra, jobId));

        LoadBalancerConfiguration configuration = mockConfiguration(batchSize, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(
                runtime, configuration, client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        assertTrue(service.addLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(service.getJobLoadBalancers(jobId).toBlocking().first()).isEqualTo(loadBalancerId);

        testScheduler.triggerActions();

        // first errored and got skipped
        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client, times(2)).registerAll(eq(loadBalancerId), argThat(targets -> targets != null && targets.size() == batchSize));
        verify(client, atMost(2)).deregisterAll(eq(loadBalancerId), argThat(CollectionsExt::isNullOrEmpty));
    }

    @Test
    public void removeLoadBalancerDeregisterKnownTargets() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();
        final JobLoadBalancer jobLoadBalancer = new JobLoadBalancer(jobId, loadBalancerId);

        defaultStubs();

        assertTrue(loadBalancerStore.addOrUpdateLoadBalancer(jobLoadBalancer, JobLoadBalancer.State.Associated)
                .await(100, TimeUnit.MILLISECONDS));
        final Map<LoadBalancerTarget, LoadBalancerTarget.State> existingTargets = CollectionsExt.<LoadBalancerTarget, LoadBalancerTarget.State>newHashMap()
                .entry(new LoadBalancerTarget(jobLoadBalancer, "Task-1", "1.1.1.1"), LoadBalancerTarget.State.Registered)
                .entry(new LoadBalancerTarget(jobLoadBalancer, "Task-2", "2.2.2.2"), LoadBalancerTarget.State.Registered)
                // should keep retrying targets that already have been marked to be deregistered
                .entry(new LoadBalancerTarget(jobLoadBalancer, "Task-3", "3.3.3.3"), LoadBalancerTarget.State.Deregistered)
                .entry(new LoadBalancerTarget(jobLoadBalancer, "Task-4", "4.4.4.4"), LoadBalancerTarget.State.Deregistered)
                .toMap();
        assertTrue(targetTracking.updateTargets(existingTargets).await(100, TimeUnit.MILLISECONDS));
        assertEquals(count(targetTracking.retrieveTargets(jobLoadBalancer)), 4);

        LoadBalancerConfiguration configuration = mockConfiguration(4, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(runtime, configuration, client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        assertTrue(service.removeLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        final LoadBalancerState loadBalancerState = loadBalancerStore.retrieveLoadBalancersForJob(jobId).toBlocking().first();
        assertEquals(loadBalancerState.getLoadBalancerId(), loadBalancerId);
        assertEquals(loadBalancerState.getState(), JobLoadBalancer.State.Dissociated);
        assertFalse(service.getJobLoadBalancers(jobId).toBlocking().getIterator().hasNext());

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client, never()).registerAll(eq(loadBalancerId), any());
        verify(client).deregisterAll(eq(loadBalancerId), argThat(targets -> targets != null && targets.size() == 4));

        // all successfully deregistered are gone
        assertEquals(count(targetTracking.retrieveTargets(jobLoadBalancer)), 0);
    }

    @Test
    public void removeSkipLoadBalancerOperationsOnErrors() throws Exception {
        final String firstJobId = UUID.randomUUID().toString();
        final String secondJobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();
        final JobLoadBalancer firstLoadBalancer = new JobLoadBalancer(firstJobId, loadBalancerId);
        final JobLoadBalancer secondLoadBalancer = new JobLoadBalancer(secondJobId, loadBalancerId);

        defaultStubs();
        loadBalancerStore = spy(loadBalancerStore);
        targetTracking = spy(targetTracking);
        when(targetTracking.retrieveTargets(new JobLoadBalancer(firstJobId, loadBalancerId))).thenThrow(new RuntimeException());
        assertTrue(loadBalancerStore.addOrUpdateLoadBalancer(firstLoadBalancer, JobLoadBalancer.State.Associated)
                .await(100, TimeUnit.MILLISECONDS));
        assertTrue(loadBalancerStore.addOrUpdateLoadBalancer(secondLoadBalancer, JobLoadBalancer.State.Associated)
                .await(100, TimeUnit.MILLISECONDS));
        Map<LoadBalancerTarget, LoadBalancerTarget.State> existingTargets = CollectionsExt.<LoadBalancerTarget, LoadBalancerTarget.State>newHashMap()
                .entry(new LoadBalancerTarget(secondLoadBalancer, "Task-1", "1.1.1.1"), LoadBalancerTarget.State.Registered)
                .entry(new LoadBalancerTarget(secondLoadBalancer, "Task-2", "2.2.2.2"), LoadBalancerTarget.State.Registered)
                .toMap();
        assertTrue(targetTracking.updateTargets(existingTargets).await(100, TimeUnit.MILLISECONDS));
        assertEquals(count(targetTracking.retrieveTargets(secondLoadBalancer)), 2);

        LoadBalancerConfiguration configuration = mockConfiguration(2, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(
                runtime, configuration, client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        // first fails
        assertTrue(service.removeLoadBalancer(firstJobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertFalse(service.getJobLoadBalancers(firstJobId).toBlocking().getIterator().hasNext());

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).deregisterAll(any(), any());
        verify(client, never()).registerAll(any(), any());
        assertEquals(count(targetTracking.retrieveTargets(secondLoadBalancer)), 2);

        // second succeeds
        assertTrue(service.removeLoadBalancer(secondJobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertFalse(service.getJobLoadBalancers(firstJobId).toBlocking().getIterator().hasNext());

        testScheduler.triggerActions();

        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client, never()).registerAll(eq(loadBalancerId), any());
        verify(client).deregisterAll(eq(loadBalancerId), argThat(targets -> targets != null && targets.size() == 2));
        // all successfully deregistered are gone
        assertEquals(count(targetTracking.retrieveTargets(secondLoadBalancer)), 0);
    }

    @Test
    public void goneJobsAreSkipped() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();

        defaultStubs();
        applyValidGetJobMock(jobOperations, jobId);
        // job is gone somewhere in the middle after its pipeline starts
        when(jobOperations.getTasks(jobId)).thenThrow(JobManagerException.jobNotFound(jobId));

        LoadBalancerConfiguration configuration = mockConfiguration(1, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(
                runtime, configuration, client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        assertTrue(service.addLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(service.getJobLoadBalancers(jobId).toBlocking().first()).isEqualTo(loadBalancerId);

        testScheduler.triggerActions();

        // job errored and got skipped
        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).registerAll(any(), any());
        verify(client, never()).deregisterAll(any(), any());
    }

    @Test
    public void newTasksGetRegistered() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String taskId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();
        final PublishSubject<JobManagerEvent<?>> taskEvents = PublishSubject.create();

        when(client.registerAll(any(), any())).thenReturn(Completable.complete());
        when(client.deregisterAll(any(), any())).thenReturn(Completable.complete());
        when(jobOperations.observeJobs()).thenReturn(taskEvents);
        when(jobOperations.getTasks(jobId)).thenReturn(Collections.emptyList());
        applyValidGetJobMock(jobOperations, jobId);

        LoadBalancerConfiguration configuration = mockConfiguration(1, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(
                runtime, configuration, client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        assertTrue(service.addLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(service.getJobLoadBalancers(jobId).toBlocking().first()).isEqualTo(loadBalancerId);

        testScheduler.triggerActions();
        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).registerAll(any(), any());
        verify(client, never()).deregisterAll(any(), any());

        Task launched = ServiceJobTask.newBuilder()
                .withJobId(jobId)
                .withId(taskId)
                .withStatus(TaskStatus.newBuilder().withState(TaskState.Launched).build())
                .build();

        Task startingWithIp = launched.toBuilder()
                .withStatus(TaskStatus.newBuilder().withState(TaskState.StartInitiated).build())
                .withTaskContext(CollectionsExt.asMap(
                        TaskAttributes.TASK_ATTRIBUTES_CONTAINER_IP, "1.2.3.4"
                )).build();

        Task started = startingWithIp.toBuilder()
                .withStatus(TaskStatus.newBuilder().withState(TaskState.Started).build())
                .build();

        // events with no state transition gets ignored
        taskEvents.onNext(TaskUpdateEvent.newTask(null, launched));

        testScheduler.triggerActions();
        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).registerAll(any(), any());
        verify(client, never()).deregisterAll(any(), any());

        // events to !Started states get ignored
        taskEvents.onNext(TaskUpdateEvent.taskChange(null, startingWithIp, launched));

        testScheduler.triggerActions();
        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).registerAll(any(), any());
        verify(client, never()).deregisterAll(any(), any());

        // finally detect the task is UP and gets registered
        taskEvents.onNext(TaskUpdateEvent.taskChange(null, started, startingWithIp));

        testScheduler.triggerActions();
        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client).registerAll(eq(loadBalancerId), argThat(set -> set.contains("1.2.3.4")));
        verify(client, never()).deregisterAll(eq(loadBalancerId), any());
    }

    @Test
    public void finishedTasksGetDeregistered() throws Exception {
        final String jobId = UUID.randomUUID().toString();
        final String loadBalancerId = "lb-" + UUID.randomUUID().toString();
        final PublishSubject<JobManagerEvent<?>> taskEvents = PublishSubject.create();

        when(client.registerAll(any(), any())).thenReturn(Completable.complete());
        when(client.deregisterAll(any(), any())).thenReturn(Completable.complete());
        when(jobOperations.observeJobs()).thenReturn(taskEvents);
        when(jobOperations.getTasks(jobId)).thenReturn(Collections.emptyList());
        applyValidGetJobMock(jobOperations, jobId);

        LoadBalancerConfiguration configuration = mockConfiguration(1, 5_000);
        DefaultLoadBalancerService service = new DefaultLoadBalancerService(
                runtime, configuration, client, loadBalancerStore, jobOperations, targetTracking, validator, testScheduler);

        final AssertableSubscriber<Batch> testSubscriber = service.events().test();

        assertTrue(service.addLoadBalancer(jobId, loadBalancerId).await(100, TimeUnit.MILLISECONDS));
        assertThat(service.getJobLoadBalancers(jobId).toBlocking().first()).isEqualTo(loadBalancerId);

        testScheduler.triggerActions();
        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).registerAll(any(), any());
        verify(client, never()).deregisterAll(any(), any());

        // a task that was prematurely killed before having an IP address associated to it should be ignored
        Task noIp = ServiceJobTask.newBuilder()
                .withJobId(jobId)
                .withId(UUID.randomUUID().toString())
                .withStatus(TaskStatus.newBuilder().withState(TaskState.KillInitiated).build())
                .build();
        Task noIpFinished = noIp.toBuilder()
                .withStatus(TaskStatus.newBuilder().withState(TaskState.Finished).build())
                .build();
        taskEvents.onNext(TaskUpdateEvent.taskChange(null, noIpFinished, noIp));

        testScheduler.triggerActions();
        testSubscriber.assertNoErrors().assertValueCount(0);
        verify(client, never()).registerAll(any(), any());
        verify(client, never()).deregisterAll(any(), any());

        // 3 state transitions to 3 different terminal events

        Task first = noIp.toBuilder()
                .withId(UUID.randomUUID().toString())
                .withStatus(TaskStatus.newBuilder().withState(TaskState.Started).build())
                .withTaskContext(CollectionsExt.asMap(
                        TaskAttributes.TASK_ATTRIBUTES_CONTAINER_IP, "1.1.1.1"
                )).build();
        Task firstFinished = first.toBuilder()
                .withStatus(TaskStatus.newBuilder().withState(TaskState.Finished).build())
                .build();
        taskEvents.onNext(TaskUpdateEvent.taskChange(null, firstFinished, first));

        testScheduler.triggerActions();
        testSubscriber.assertNoErrors().assertValueCount(1);
        verify(client, never()).registerAll(eq(loadBalancerId), any());
        verify(client).deregisterAll(eq(loadBalancerId), argThat(set -> set.contains("1.1.1.1")));

        Task second = first.toBuilder()
                .withId(UUID.randomUUID().toString())
                .withTaskContext(CollectionsExt.asMap(
                        TaskAttributes.TASK_ATTRIBUTES_CONTAINER_IP, "2.2.2.2"
                )).build();
        Task secondKilling = second.toBuilder()
                .withStatus(TaskStatus.newBuilder().withState(TaskState.KillInitiated).build())
                .build();
        taskEvents.onNext(TaskUpdateEvent.taskChange(null, secondKilling, second));

        testScheduler.triggerActions();
        testSubscriber.assertNoErrors().assertValueCount(2);
        verify(client, never()).registerAll(eq(loadBalancerId), any());
        verify(client).deregisterAll(eq(loadBalancerId), argThat(set -> set.contains("2.2.2.2")));

        Task third = first.toBuilder()
                .withId(UUID.randomUUID().toString())
                .withTaskContext(CollectionsExt.asMap(
                        TaskAttributes.TASK_ATTRIBUTES_CONTAINER_IP, "3.3.3.3"
                )).build();
        Task thirdDisconnected = third.toBuilder()
                .withStatus(TaskStatus.newBuilder().withState(TaskState.Disconnected).build())
                .build();
        taskEvents.onNext(TaskUpdateEvent.taskChange(null, thirdDisconnected, third));

        testScheduler.triggerActions();
        testSubscriber.assertNoErrors().assertValueCount(3);
        verify(client, never()).registerAll(eq(loadBalancerId), any());
        verify(client).deregisterAll(eq(loadBalancerId), argThat(set -> set.contains("3.3.3.3")));
    }
}