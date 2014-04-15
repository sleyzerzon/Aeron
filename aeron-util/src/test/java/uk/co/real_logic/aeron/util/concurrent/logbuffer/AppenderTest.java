/*
 * Copyright 2014 Real Logic Ltd.
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
package uk.co.real_logic.aeron.util.concurrent.logbuffer;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;

import static java.lang.Integer.valueOf;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.aeron.util.BitUtil.SIZE_OF_INT;
import static uk.co.real_logic.aeron.util.BitUtil.align;
import static uk.co.real_logic.aeron.util.concurrent.logbuffer.FrameDescriptor.*;
import static uk.co.real_logic.aeron.util.concurrent.logbuffer.LogBufferDescriptor.PADDING_MSG_TYPE;
import static uk.co.real_logic.aeron.util.concurrent.logbuffer.LogBufferDescriptor.STATE_BUFFER_LENGTH;
import static uk.co.real_logic.aeron.util.concurrent.logbuffer.LogBufferDescriptor.TAIL_COUNTER_OFFSET;

public class AppenderTest
{
    private static final int LOG_BUFFER_CAPACITY = 1024 * 16;
    private static final int STATE_BUFFER_CAPACITY = STATE_BUFFER_LENGTH;
    private static final int MAX_FRAME_LENGTH = 1024;
    private static final byte[] DEFAULT_HEADER = new byte[BASE_HEADER_LENGTH + SIZE_OF_INT];

    private final AtomicBuffer logBuffer = mock(AtomicBuffer.class);
    private final AtomicBuffer stateBuffer = mock(AtomicBuffer.class);

    private Appender appender;

    @Before
    public void setUp()
    {
        when(valueOf(logBuffer.capacity())).thenReturn(valueOf(LOG_BUFFER_CAPACITY));
        when(valueOf(stateBuffer.capacity())).thenReturn(valueOf(STATE_BUFFER_CAPACITY));

        appender = new Appender(logBuffer, stateBuffer, DEFAULT_HEADER, MAX_FRAME_LENGTH);
    }

    @Test
    public void shouldReportCapacity()
    {
        assertThat(valueOf(appender.capacity()), is(valueOf(LOG_BUFFER_CAPACITY)));
    }

    @Test
    public void shouldReportMaxFrameLength()
    {
        assertThat(valueOf(appender.maxFrameLength()), is(valueOf(MAX_FRAME_LENGTH)));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnInsufficientCapacityForLog()
    {
        when(valueOf(logBuffer.capacity())).thenReturn(valueOf(LogBufferDescriptor.LOG_MIN_SIZE - 1));

        appender = new Appender(logBuffer, stateBuffer, DEFAULT_HEADER, MAX_FRAME_LENGTH);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionWhenCapacityNotMultipleOfAlignment()
    {
        final int logBufferCapacity = LogBufferDescriptor.LOG_MIN_SIZE + FRAME_ALIGNMENT + 1;
        when(valueOf(logBuffer.capacity())).thenReturn(valueOf(logBufferCapacity));

        appender = new Appender(logBuffer, stateBuffer, DEFAULT_HEADER, MAX_FRAME_LENGTH);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnInsufficientStateBufferCapacity()
    {
        when(valueOf(stateBuffer.capacity())).thenReturn(valueOf(LogBufferDescriptor.STATE_BUFFER_LENGTH - 1));

        appender = new Appender(logBuffer, stateBuffer, DEFAULT_HEADER, MAX_FRAME_LENGTH);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnDefaultHeaderLengthLessThanBaseHeaderLength()
    {
        int length = BASE_HEADER_LENGTH - 1;
        appender = new Appender(logBuffer, stateBuffer, new byte[length], MAX_FRAME_LENGTH);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnDefaultHeaderLengthNotOnWordSizeBoundary()
    {
        appender = new Appender(logBuffer, stateBuffer, new byte[31], MAX_FRAME_LENGTH);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnMaxFrameSizeNotOnWordSizeBoundary()
    {
        appender = new Appender(logBuffer, stateBuffer, DEFAULT_HEADER, 1001);
    }

    @Test
    public void shouldReportCurrentTail()
    {
        final int tailValue = 64;
        when(valueOf(stateBuffer.getIntVolatile(TAIL_COUNTER_OFFSET))).thenReturn(valueOf(tailValue));

        assertThat(valueOf(appender.tail()), is(valueOf(tailValue)));
    }

    @Test
    public void shouldReportCurrentTailAtCapacity()
    {
        final int tailValue = LOG_BUFFER_CAPACITY + 64;
        when(valueOf(stateBuffer.getIntVolatile(TAIL_COUNTER_OFFSET))).thenReturn(valueOf(tailValue));

        assertThat(valueOf(appender.tail()), is(valueOf(LOG_BUFFER_CAPACITY)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionWhenMaxMessageLengthExceeded()
    {
        final int maxMessageLength = appender.maxMessageLength();
        final AtomicBuffer srcBuffer = new AtomicBuffer(new byte[1024]);

        appender.append(srcBuffer, 0, maxMessageLength + 1);
    }

    @Test
    public void shouldAppendFrameToEmptyLog()
    {
        when(valueOf(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, FRAME_ALIGNMENT)))
            .thenReturn(valueOf(0));

        final int headerLength = DEFAULT_HEADER.length;
        final AtomicBuffer buffer = new AtomicBuffer(new byte[128]);
        final int msgLen = 20;
        final int tail = 0;

        assertTrue(appender.append(buffer, 0, msgLen));

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, FRAME_ALIGNMENT);
        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putBytes(headerLength, buffer, 0, msgLen);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), UNFRAGMENTED);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), FRAME_ALIGNMENT);
    }

    @Test
    public void shouldAppendFrameTwiceToLog()
    {
        when(valueOf(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, FRAME_ALIGNMENT)))
            .thenReturn(valueOf(0))
            .thenReturn(valueOf(FRAME_ALIGNMENT));

        final int headerLength = DEFAULT_HEADER.length;
        final AtomicBuffer buffer = new AtomicBuffer(new byte[128]);
        final int msgLen = 20;
        int tail = 0;

        assertTrue(appender.append(buffer, 0, msgLen));
        assertTrue(appender.append(buffer, 0, msgLen));

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, FRAME_ALIGNMENT);
        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putBytes(headerLength, buffer, 0, msgLen);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), UNFRAGMENTED);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), FRAME_ALIGNMENT);

        tail = FRAME_ALIGNMENT;
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, FRAME_ALIGNMENT);
        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putBytes(tail + headerLength, buffer, 0, msgLen);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), UNFRAGMENTED);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), FRAME_ALIGNMENT);
    }

    @Test
    public void shouldFailToAppendToLogAtCapacity()
    {
        when(valueOf(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, FRAME_ALIGNMENT)))
            .thenReturn(valueOf(appender.capacity()));

        final AtomicBuffer buffer = new AtomicBuffer(new byte[128]);
        final int msgLength = 20;

        assertFalse(appender.append(buffer, 0, msgLength));

        verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, FRAME_ALIGNMENT);
        verify(logBuffer, atLeastOnce()).capacity();
        verifyNoMoreInteractions(logBuffer);
    }

    @Test
    public void shouldPadLogAndFailAppendOnInsufficientRemainingCapacity()
    {
        final int msgLength = 120;
        final int headerLength = DEFAULT_HEADER.length;
        final int requiredFrameSize = align(headerLength + msgLength, FRAME_ALIGNMENT);
        final int tailValue = appender.capacity() - FRAME_ALIGNMENT;
        when(valueOf(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, requiredFrameSize)))
            .thenReturn(valueOf(tailValue));

        final AtomicBuffer buffer = new AtomicBuffer(new byte[128]);

        assertFalse(appender.append(buffer, 0, msgLength));

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, requiredFrameSize);
        inOrder.verify(logBuffer, times(1)).putBytes(tailValue, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putShort(typeOffset(tailValue), PADDING_MSG_TYPE, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tailValue), UNFRAGMENTED);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tailValue), tailValue, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tailValue), LOG_BUFFER_CAPACITY - tailValue);
    }

    @Test
    public void shouldFragmentMessageOverTwoFrames()
    {
        final int msgLen = appender.maxPayloadLength() + 1;
        final int headerLength = DEFAULT_HEADER.length;
        final int requiredCapacity = align(headerLength + 1, FRAME_ALIGNMENT) + appender.maxFrameLength();
        when(valueOf(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, requiredCapacity)))
            .thenReturn(valueOf(0));

        final AtomicBuffer buffer = new AtomicBuffer(new byte[msgLen]);

        assertTrue(appender.append(buffer, 0, msgLen));

        int tail  = 0;
        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, requiredCapacity);

        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putBytes(tail + headerLength, buffer, 0, appender.maxPayloadLength());
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), BEGIN_FRAG);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), appender.maxFrameLength());

        tail = appender.maxFrameLength();
        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putBytes(tail + headerLength, buffer, appender.maxPayloadLength(), 1);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), END_FRAG);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), FRAME_ALIGNMENT);
    }
}