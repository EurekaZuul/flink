/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.resourcemanager.slotmanager;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.resourcemanager.WorkerResourceSpec;
import org.apache.flink.runtime.resourcemanager.registration.TaskExecutorConnection;
import org.apache.flink.runtime.taskexecutor.SlotReport;
import org.apache.flink.runtime.taskexecutor.SlotStatus;
import org.apache.flink.runtime.taskexecutor.TestingTaskExecutorGatewayBuilder;
import org.apache.flink.testutils.TestingUtils;
import org.apache.flink.testutils.executor.TestExecutorResource;
import org.apache.flink.util.TestLogger;
import org.apache.flink.util.concurrent.ScheduledExecutorServiceAdapter;

import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/** Tests for the {@link TaskExecutorManager}. */
public class TaskExecutorManagerTest extends TestLogger {

    @ClassRule
    public static final TestExecutorResource<ScheduledExecutorService> EXECUTOR_RESOURCE =
            TestingUtils.defaultExecutorResource();

    /** Tests that a pending slot is only fulfilled by an exactly matching received slot. */
    @Test
    public void testPendingSlotNotFulfilledIfProfilesAreNotExactMatch() {
        final int numWorkerCpuCores = 3;
        final WorkerResourceSpec workerResourceSpec =
                new WorkerResourceSpec.Builder().setCpuCores(numWorkerCpuCores).build();
        final ResourceProfile requestedSlotProfile =
                ResourceProfile.newBuilder().setCpuCores(numWorkerCpuCores).build();
        final ResourceProfile offeredSlotProfile =
                ResourceProfile.newBuilder().setCpuCores(numWorkerCpuCores - 1).build();

        try (final TaskExecutorManager taskExecutorManager =
                createTaskExecutorManagerBuilder()
                        .setDefaultWorkerResourceSpec(workerResourceSpec)
                        .setNumSlotsPerWorker(
                                1) // set to one so that the slot profiles directly correspond to
                        // the worker spec
                        .setMaxNumSlots(2)
                        .createTaskExecutorManager()) {

            // create pending slot
            taskExecutorManager.allocateWorker(requestedSlotProfile);
            assertThat(taskExecutorManager.getNumberPendingTaskManagerSlots(), is(1));

            createAndRegisterTaskExecutor(taskExecutorManager, 1, offeredSlotProfile);

            // the slot from the task executor should be accepted, but we should still be waiting
            // for the originally requested slot
            assertThat(taskExecutorManager.getNumberRegisteredSlots(), is(1));
            assertThat(taskExecutorManager.getNumberPendingTaskManagerSlots(), is(1));
        }
    }

    /** Tests that a pending slot is not fulfilled by an already allocated slot. */
    @Test
    public void testPendingSlotNotFulfilledByAllocatedSlot() {
        final int numWorkerCpuCores = 3;
        final WorkerResourceSpec workerResourceSpec =
                new WorkerResourceSpec.Builder().setCpuCores(numWorkerCpuCores).build();
        final ResourceProfile requestedSlotProfile =
                ResourceProfile.newBuilder().setCpuCores(numWorkerCpuCores).build();

        try (final TaskExecutorManager taskExecutorManager =
                createTaskExecutorManagerBuilder()
                        .setDefaultWorkerResourceSpec(workerResourceSpec)
                        .setNumSlotsPerWorker(
                                1) // set to one so that the slot profiles directly correspond to
                        // the worker spec
                        .setMaxNumSlots(2)
                        .createTaskExecutorManager()) {

            // create pending slot
            taskExecutorManager.allocateWorker(requestedSlotProfile);
            assertThat(taskExecutorManager.getNumberPendingTaskManagerSlots(), is(1));

            final TaskExecutorConnection taskExecutorConnection = createTaskExecutorConnection();
            final SlotReport slotReport =
                    new SlotReport(
                            new SlotStatus(
                                    new SlotID(taskExecutorConnection.getResourceID(), 0),
                                    requestedSlotProfile,
                                    JobID.generate(),
                                    new AllocationID()));
            taskExecutorManager.registerTaskManager(
                    taskExecutorConnection, slotReport, ResourceProfile.ANY, ResourceProfile.ANY);

            // the slot from the task executor should be accepted, but we should still be waiting
            // for the originally requested slot
            assertThat(taskExecutorManager.getNumberRegisteredSlots(), is(1));
            assertThat(taskExecutorManager.getNumberPendingTaskManagerSlots(), is(1));
        }
    }

    /**
     * Tests that a task manager timeout does not remove the slots from the SlotManager. A timeout
     * should only trigger the {@link ResourceAllocator#declareResourceNeeded} callback. The
     * receiver of the callback can then decide what to do with the TaskManager.
     *
     * <p>See FLINK-7793
     */
    @Test
    public void testTaskManagerTimeoutDoesNotRemoveSlots() throws Exception {
        final Time taskManagerTimeout = Time.milliseconds(10L);

        final CompletableFuture<InstanceID> releaseResourceFuture = new CompletableFuture<>();
        final ResourceAllocator resourceAllocator =
                createResourceAllocatorBuilder()
                        .setDeclareResourceNeededConsumer(
                                (resourceDeclarations) -> {
                                    assertThat(resourceDeclarations.size(), is(1));
                                    ResourceDeclaration resourceDeclaration =
                                            resourceDeclarations.iterator().next();
                                    assertThat(resourceDeclaration.getNumNeeded(), is(0));
                                    assertThat(
                                            resourceDeclaration.getUnwantedWorkers().size(), is(1));
                                    releaseResourceFuture.complete(
                                            resourceDeclaration
                                                    .getUnwantedWorkers()
                                                    .iterator()
                                                    .next());
                                })
                        .build();

        final Executor mainThreadExecutor = EXECUTOR_RESOURCE.getExecutor();

        try (final TaskExecutorManager taskExecutorManager =
                createTaskExecutorManagerBuilder()
                        .setTaskManagerTimeout(taskManagerTimeout)
                        .setResourceAllocator(resourceAllocator)
                        .setMainThreadExecutor(mainThreadExecutor)
                        .createTaskExecutorManager()) {

            CompletableFuture.supplyAsync(
                            () -> {
                                InstanceID newTaskExecutorId =
                                        createAndRegisterTaskExecutor(
                                                taskExecutorManager, 1, ResourceProfile.ANY);
                                assertEquals(1, taskExecutorManager.getNumberRegisteredSlots());
                                return newTaskExecutorId;
                            },
                            mainThreadExecutor)
                    // wait for the timeout to occur
                    .thenCombine(
                            releaseResourceFuture,
                            (registeredInstance, releasedInstance) -> {
                                assertThat(registeredInstance, is(releasedInstance));
                                assertEquals(1, taskExecutorManager.getNumberRegisteredSlots());
                                return registeredInstance;
                            })
                    .thenAccept(
                            taskExecutorId -> {
                                taskExecutorManager.unregisterTaskExecutor(taskExecutorId);
                                assertEquals(0, taskExecutorManager.getNumberRegisteredSlots());
                            })
                    .get();
        }
    }

    /**
     * Tests that formerly used task managers can timeout after all of their slots have been freed.
     */
    @Test
    public void testTimeoutForUnusedTaskManager() throws Exception {
        WorkerResourceSpec workerResourceSpec =
                new WorkerResourceSpec.Builder().setCpuCores(1).build();
        final ResourceProfile resourceProfile = ResourceProfile.newBuilder().setCpuCores(1).build();
        final Time taskManagerTimeout = Time.milliseconds(50L);

        final AtomicInteger declareResourceCount = new AtomicInteger(0);
        final CompletableFuture<InstanceID> releaseResourceFuture = new CompletableFuture<>();
        final ResourceAllocator resourceAllocator =
                new TestingResourceAllocatorBuilder()
                        .setDeclareResourceNeededConsumer(
                                (resourceDeclarations) -> {
                                    assertThat(resourceDeclarations.size(), is(1));
                                    ResourceDeclaration resourceDeclaration =
                                            resourceDeclarations.iterator().next();
                                    if (declareResourceCount.getAndIncrement() == 0) {
                                        assertThat(resourceDeclaration.getNumNeeded(), is(1));
                                        assertThat(
                                                resourceDeclaration.getUnwantedWorkers().size(),
                                                is(0));
                                    } else {
                                        assertThat(resourceDeclaration.getNumNeeded(), is(0));
                                        assertThat(
                                                resourceDeclaration.getUnwantedWorkers().size(),
                                                is(1));
                                        releaseResourceFuture.complete(
                                                resourceDeclaration
                                                        .getUnwantedWorkers()
                                                        .iterator()
                                                        .next());
                                    }
                                })
                        .build();

        final Executor mainThreadExecutor = EXECUTOR_RESOURCE.getExecutor();

        try (final TaskExecutorManager taskExecutorManager =
                createTaskExecutorManagerBuilder()
                        .setTaskManagerTimeout(taskManagerTimeout)
                        .setDefaultWorkerResourceSpec(workerResourceSpec)
                        .setResourceAllocator(resourceAllocator)
                        .setMainThreadExecutor(mainThreadExecutor)
                        .createTaskExecutorManager()) {

            CompletableFuture.supplyAsync(
                            () -> {
                                taskExecutorManager.allocateWorker(resourceProfile);
                                InstanceID taskExecutorId =
                                        createAndRegisterTaskExecutor(
                                                taskExecutorManager, 1, resourceProfile);

                                taskExecutorManager.occupySlot(taskExecutorId);
                                taskExecutorManager.freeSlot(taskExecutorId);

                                return taskExecutorId;
                            },
                            mainThreadExecutor)
                    // wait for the timeout to occur
                    .thenAcceptBoth(
                            releaseResourceFuture,
                            (registeredInstance, releasedInstance) ->
                                    assertThat(registeredInstance, is(releasedInstance)))
                    .get();
        }
    }

    /**
     * Test that the task executor manager only allocates new workers if their worker spec can
     * fulfill the requested resource profile.
     */
    @Test
    public void testWorkerOnlyAllocatedIfRequestedSlotCouldBeFulfilled() {
        final int numCoresPerWorker = 1;

        final WorkerResourceSpec workerResourceSpec =
                new WorkerResourceSpec.Builder().setCpuCores(numCoresPerWorker).build();

        final ResourceProfile requestedProfile =
                ResourceProfile.newBuilder().setCpuCores(numCoresPerWorker + 1).build();

        final AtomicInteger declareResourceCount = new AtomicInteger(0);
        ResourceAllocator resourceAllocator =
                createResourceAllocatorBuilder()
                        .setDeclareResourceNeededConsumer(
                                (resourceDeclarations) -> declareResourceCount.incrementAndGet())
                        .build();

        try (final TaskExecutorManager taskExecutorManager =
                createTaskExecutorManagerBuilder()
                        .setDefaultWorkerResourceSpec(workerResourceSpec)
                        .setNumSlotsPerWorker(1)
                        .setMaxNumSlots(1)
                        .setResourceAllocator(resourceAllocator)
                        .createTaskExecutorManager()) {

            assertThat(
                    taskExecutorManager.allocateWorker(requestedProfile).orElse(null), nullValue());
            assertThat(declareResourceCount.get(), is(0));
        }
    }

    /**
     * Test that the task executor manager respects the max limitation of the number of slots when
     * allocating new workers.
     */
    @Test
    public void testMaxSlotLimitAllocateWorker() {
        final int numberSlots = 1;
        final int maxSlotNum = 1;

        final List<Integer> resourceRequestNumber = new ArrayList<>();
        ResourceAllocator resourceAllocator =
                createResourceAllocatorBuilder()
                        .setDeclareResourceNeededConsumer(
                                (resourceDeclarations) -> {
                                    assertThat(resourceDeclarations.size(), is(1));
                                    ResourceDeclaration resourceDeclaration =
                                            resourceDeclarations.iterator().next();
                                    resourceRequestNumber.add(resourceDeclaration.getNumNeeded());
                                })
                        .build();

        try (final TaskExecutorManager taskExecutorManager =
                createTaskExecutorManagerBuilder()
                        .setNumSlotsPerWorker(numberSlots)
                        .setMaxNumSlots(maxSlotNum)
                        .setResourceAllocator(resourceAllocator)
                        .createTaskExecutorManager()) {

            assertThat(resourceRequestNumber.size(), is(0));

            taskExecutorManager.allocateWorker(ResourceProfile.UNKNOWN);
            assertThat(resourceRequestNumber.size(), is(1));
            assertThat(resourceRequestNumber.get(0), is(1));

            taskExecutorManager.allocateWorker(ResourceProfile.UNKNOWN);
            assertThat(resourceRequestNumber.size(), is(1));
            assertThat(resourceRequestNumber.get(0), is(1));
        }
    }

    /**
     * Test that the slot manager release resource when the number of slots exceed max limit when
     * new TaskExecutor registered.
     */
    @Test
    public void testMaxSlotLimitRegisterWorker() throws Exception {
        final int numberSlots = 1;
        final int maxSlotNum = 1;

        try (final TaskExecutorManager taskExecutorManager =
                createTaskExecutorManagerBuilder()
                        .setNumSlotsPerWorker(numberSlots)
                        .setMaxNumSlots(maxSlotNum)
                        .createTaskExecutorManager()) {

            createAndRegisterTaskExecutor(taskExecutorManager, 1, ResourceProfile.ANY);
            createAndRegisterTaskExecutor(taskExecutorManager, 1, ResourceProfile.ANY);

            assertThat(taskExecutorManager.getNumberRegisteredSlots(), is(1));
        }
    }

    @Test
    public void testGetResourceOverview() {
        final ResourceProfile resourceProfile1 = ResourceProfile.fromResources(1, 10);
        final ResourceProfile resourceProfile2 = ResourceProfile.fromResources(2, 20);

        try (final TaskExecutorManager taskExecutorManager =
                createTaskExecutorManagerBuilder().setMaxNumSlots(4).createTaskExecutorManager()) {
            final InstanceID instanceId1 =
                    createAndRegisterTaskExecutor(taskExecutorManager, 2, resourceProfile1);
            final InstanceID instanceId2 =
                    createAndRegisterTaskExecutor(taskExecutorManager, 2, resourceProfile2);
            taskExecutorManager.occupySlot(instanceId1);
            taskExecutorManager.occupySlot(instanceId2);

            assertThat(
                    taskExecutorManager.getTotalFreeResources(),
                    equalTo(resourceProfile1.merge(resourceProfile2)));
            assertThat(
                    taskExecutorManager.getTotalFreeResourcesOf(instanceId1),
                    equalTo(resourceProfile1));
            assertThat(
                    taskExecutorManager.getTotalFreeResourcesOf(instanceId2),
                    equalTo(resourceProfile2));
            assertThat(
                    taskExecutorManager.getTotalRegisteredResources(),
                    equalTo(resourceProfile1.merge(resourceProfile2).multiply(2)));
            assertThat(
                    taskExecutorManager.getTotalRegisteredResourcesOf(instanceId1),
                    equalTo(resourceProfile1.multiply(2)));
            assertThat(
                    taskExecutorManager.getTotalRegisteredResourcesOf(instanceId2),
                    equalTo(resourceProfile2.multiply(2)));
        }
    }

    private static TaskExecutorManagerBuilder createTaskExecutorManagerBuilder() {
        return new TaskExecutorManagerBuilder(
                        new ScheduledExecutorServiceAdapter(EXECUTOR_RESOURCE.getExecutor()))
                .setResourceAllocator(createResourceAllocatorBuilder().build());
    }

    private static TestingResourceAllocatorBuilder createResourceAllocatorBuilder() {
        return new TestingResourceAllocatorBuilder();
    }

    private static InstanceID createAndRegisterTaskExecutor(
            TaskExecutorManager taskExecutorManager,
            int numSlots,
            ResourceProfile resourceProfile) {
        final TaskExecutorConnection taskExecutorConnection = createTaskExecutorConnection();

        List<SlotStatus> slotStatuses =
                IntStream.range(0, numSlots)
                        .mapToObj(
                                slotNumber ->
                                        new SlotStatus(
                                                new SlotID(
                                                        taskExecutorConnection.getResourceID(),
                                                        slotNumber),
                                                resourceProfile))
                        .collect(Collectors.toList());

        final SlotReport slotReport = new SlotReport(slotStatuses);

        taskExecutorManager.registerTaskManager(
                taskExecutorConnection,
                slotReport,
                resourceProfile.multiply(numSlots),
                resourceProfile);

        return taskExecutorConnection.getInstanceID();
    }

    private static TaskExecutorConnection createTaskExecutorConnection() {
        return new TaskExecutorConnection(
                ResourceID.generate(),
                new TestingTaskExecutorGatewayBuilder().createTestingTaskExecutorGateway());
    }
}
