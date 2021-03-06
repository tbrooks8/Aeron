/*
 * Copyright 2014 - 2017 Real Logic Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.aeron.archiver;

import io.aeron.archiver.messages.*;
import org.agrona.*;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;

class ArchiveIndex implements AutoCloseable
{
    public static final int NULL_STREAM_INSTANCE_ID = -1;
    public static final int PAGE_SIZE = 4096;
    public static final int CAPACITY = 4096;
    public static final int INDEX_FRAME_LENGTH = 8;
    public static final int EOF_MARKER = -1;

    private final ArchiveStartedNotificationEncoder archiveStartedNotificationEncoder =
        new ArchiveStartedNotificationEncoder();

    // TODO: Object2int map cound spare us the boxing here
    private final HashMap<StreamInstance, Integer> streamInstance2InstanceId = new HashMap<>();
    private final Int2ObjectHashMap<StreamInstance> instanceId2streamInstance = new Int2ObjectHashMap<>();
    private final File archiveFolder;
    private final ByteBuffer byteBuffer;
    private final UnsafeBuffer unsafeBuffer;
    private final FileChannel archiveIndexFileChannel;
    private int streamInstanceIdSeq = 0;

    // TODO: archiver resume after restart needs consideration, would imply instance to id is one to many
    // TODO: re-subscription to same channel:stream can result in same streamInstance -> error, needs consideration

    ArchiveIndex(final File archiveFolder)
    {
        this.archiveFolder = archiveFolder;
        try
        {
            // TODO: refactor file interaction to a separate class
            final RandomAccessFile archiveIndexFile =
                new RandomAccessFile(new File(archiveFolder, "index"), "rw");
            archiveIndexFileChannel = archiveIndexFile.getChannel();
            byteBuffer = BufferUtil.allocateDirectAligned(CAPACITY, PAGE_SIZE);
            unsafeBuffer = new UnsafeBuffer(byteBuffer);
            final ArchiveStartedNotificationDecoder decoder = new ArchiveStartedNotificationDecoder();
            int offset;
            while (archiveIndexFileChannel.read(byteBuffer) != -1)
            {
                offset = 0;
                byteBuffer.flip();
                if (byteBuffer.remaining() == 0)
                {
                    break;
                }
                int read;
                while (offset < CAPACITY &&
                       (read = loadIntoIndex(byteBuffer, unsafeBuffer, decoder, offset)) != 0)
                {
                    offset += read;
                }
                byteBuffer.flip();
            }
            final long position = archiveIndexFileChannel.position();
            if (position > 0)
            {
                archiveIndexFileChannel.position(position - 4);
            }
        }
        catch (IOException e)
        {
            LangUtil.rethrowUnchecked(e);
            throw new RuntimeException();
        }
        archiveStartedNotificationEncoder.wrap(unsafeBuffer, INDEX_FRAME_LENGTH);
    }

    private int loadIntoIndex(final ByteBuffer dst,
                              final UnsafeBuffer unsafeBuffer,
                              final ArchiveStartedNotificationDecoder decoder,
                              final int offset)
    {

        if (dst.remaining() == 0)
        {
            return 0;
        }

        if (dst.remaining() < INDEX_FRAME_LENGTH)
        {
            dst.compact();
            if (dst.capacity() - dst.limit() < INDEX_FRAME_LENGTH)
            {
                throw new IllegalStateException("After compaction this buffer still too small to fit " +
                                                "INDEX_FRAME_LENGTH=8");
            }
            return 0;
        }

        // frame
        final int length = unsafeBuffer.getInt(offset);
        if (length + INDEX_FRAME_LENGTH > CAPACITY)
        {
            throw new IllegalStateException("Frame and record combined exceed max allowed size:" + CAPACITY);
        }
        if (length == EOF_MARKER)
        {
            // EOF marker
            return 0;
        }
        if (offset + INDEX_FRAME_LENGTH + length > CAPACITY)
        {
            dst.compact();
            return 0;
        }

        decoder.wrap(
            unsafeBuffer,
            offset + INDEX_FRAME_LENGTH,
            ArchiveStartedNotificationDecoder.BLOCK_LENGTH,
            ArchiveStartedNotificationDecoder.SCHEMA_VERSION);

        final int streamInstanceId = decoder.streamInstanceId();
        final int sessionId = decoder.sessionId();
        final int streamId = decoder.streamId();
        final String source = decoder.source();
        final String channel = decoder.channel();

        final StreamInstance newStreamInstance = new StreamInstance(source, sessionId, channel, streamId);
        streamInstance2InstanceId.put(newStreamInstance, streamInstanceId);
        instanceId2streamInstance.put(streamInstanceId, newStreamInstance);

        // TODO: validate metadata per archive on load of index to avoid disappointment

        streamInstanceIdSeq = Math.max(streamInstanceId + 1, streamInstanceIdSeq);
        return length + INDEX_FRAME_LENGTH;
    }

    int addNewStreamInstance(final StreamInstance newStreamInstance)
    {
        if (streamInstance2InstanceId.containsKey(newStreamInstance))
        {
            throw new IllegalArgumentException("Stream instance already exists in index");
        }
        final int newStreamInstanceId = streamInstanceIdSeq;

        archiveStartedNotificationEncoder.limit(INDEX_FRAME_LENGTH + ArchiveStartedNotificationEncoder.BLOCK_LENGTH);
        archiveStartedNotificationEncoder
            .streamInstanceId(newStreamInstanceId)
            .sessionId(newStreamInstance.sessionId())
            .streamId(newStreamInstance.streamId())
            .source(newStreamInstance.source())
            .channel(newStreamInstance.channel());

        // keep word alignment like a good SBE user
        final int encodedLength = BitUtil.align(archiveStartedNotificationEncoder.encodedLength(), 8);
        unsafeBuffer.putInt(0, encodedLength);
        // if this were a mmapped file this last right would need to be ordered
        unsafeBuffer.putInt(encodedLength + 8, -1);
        byteBuffer.position(0).limit(8 + encodedLength + 4);
        try
        {
            archiveIndexFileChannel.write(byteBuffer);

            archiveIndexFileChannel.force(false);
            archiveIndexFileChannel.position(archiveIndexFileChannel.position() - 4);
        }
        catch (Exception e)
        {
            LangUtil.rethrowUnchecked(e);
        }
        streamInstanceIdSeq++;
        streamInstance2InstanceId.put(newStreamInstance, newStreamInstanceId);
        instanceId2streamInstance.put(newStreamInstanceId, newStreamInstance);

        return newStreamInstanceId;
    }

    int getStreamInstanceId(final StreamInstance newStreamInstance)
    {
        final Integer streamInstanceId = streamInstance2InstanceId.get(newStreamInstance);

        return streamInstanceId == null ? NULL_STREAM_INSTANCE_ID : streamInstanceId;
    }

    @Override
    public void close() throws Exception
    {
        // TODO: wish I could delete that buffer
        archiveIndexFileChannel.close();
    }

    StreamInstance getStreamInstance(final int newStreamInstanceId)
    {
        return instanceId2streamInstance.get(newStreamInstanceId);
    }
}
