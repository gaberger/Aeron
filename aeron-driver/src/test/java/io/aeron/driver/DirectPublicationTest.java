/*
 * Copyright 2015 - 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver;

import io.aeron.driver.buffer.RawLogFactory;
import io.aeron.driver.status.SystemCounters;
import org.junit.Before;
import org.junit.Test;
import io.aeron.CommonContext;
import io.aeron.DriverProxy;
import org.agrona.concurrent.*;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.status.CountersManager;
import org.agrona.concurrent.status.Position;
import org.agrona.concurrent.status.UnsafeBufferPosition;

import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static io.aeron.logbuffer.LogBufferDescriptor.TERM_META_DATA_LENGTH;

public class DirectPublicationTest
{
    private static final int STREAM_ID = 10;
    private static final int TERM_BUFFER_LENGTH = Configuration.TERM_BUFFER_LENGTH_DEFAULT;
    private static final int BUFFER_LENGTH = 1024 * 16;

    private Position publisherLimit;
    private DirectPublication directPublication;

    private DriverProxy driverProxy;
    private DriverConductor driverConductor;

    private long currentTime = 0;
    private NanoClock nanoClock = () -> currentTime;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() throws Exception
    {
        final RingBuffer fromClientCommands =
            new ManyToOneRingBuffer(new UnsafeBuffer(
                ByteBuffer.allocateDirect(Configuration.CONDUCTOR_BUFFER_LENGTH)));

        final RawLogFactory mockRawLogFactory = mock(RawLogFactory.class);
        final UnsafeBuffer counterBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_LENGTH));
        final CountersManager countersManager = new CountersManager(
            new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_LENGTH * 2)), counterBuffer);

        when(mockRawLogFactory.newDirectPublication(anyInt(), anyInt(), anyLong(), anyInt()))
            .thenReturn(LogBufferHelper.newTestLogBuffers(TERM_BUFFER_LENGTH, TERM_META_DATA_LENGTH));

        final MediaDriver.Context ctx = new MediaDriver.Context()
            .toDriverCommands(fromClientCommands)
            .rawLogBuffersFactory(mockRawLogFactory)
            .clientProxy(mock(ClientProxy.class))
            .toConductorFromReceiverCommandQueue(mock(OneToOneConcurrentArrayQueue.class))
            .toConductorFromSenderCommandQueue(mock(OneToOneConcurrentArrayQueue.class))
            .epochClock(new SystemEpochClock())
            .countersManager(countersManager)
            .systemCounters(mock(SystemCounters.class))
            .nanoClock(nanoClock);

        ctx.countersValuesBuffer(counterBuffer);

        driverProxy = new DriverProxy(fromClientCommands);
        driverConductor = new DriverConductor(ctx);

        // have a conductor construct one for us
        driverProxy.addPublication(CommonContext.IPC_CHANNEL, STREAM_ID);
        driverConductor.doWork();

        directPublication = driverConductor.getDirectPublication(STREAM_ID);

        publisherLimit = new UnsafeBufferPosition(counterBuffer, directPublication.publisherLimitId());
    }

    @Test
    public void shouldStartWithPublisherLimitSetToZero()
    {
        assertThat(publisherLimit.get(), is(0L));
    }

    @Test
    public void shouldKeepPublisherLimitZeroOnNoSubscriptionUpdate()
    {
        directPublication.updatePublishersLimit(0);
        assertThat(publisherLimit.get(), is(0L));
    }

    @Test
    public void shouldHaveJoiningPositionZeroWhenNoSubscriptions()
    {
        assertThat(directPublication.joiningPosition(), is(0L));
    }

    @Test
    public void shouldIncrementPublisherLimitOnSubscription() throws Exception
    {
        driverProxy.addSubscription(CommonContext.IPC_CHANNEL, STREAM_ID);
        driverConductor.doWork();

        assertThat(publisherLimit.get(), is(greaterThan(0L)));
    }
}
