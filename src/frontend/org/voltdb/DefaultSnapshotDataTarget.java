/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hadoop_voltpatches.util.PureJavaCrc32;
import org.apache.hadoop_voltpatches.util.PureJavaCrc32C;
import org.json_voltpatches.JSONObject;
import org.json_voltpatches.JSONStringer;
import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.CoreUtils;
import org.voltcore.utils.DBBPool;
import org.voltcore.utils.DBBPool.BBContainer;
import org.voltdb.messaging.FastSerializer;
import org.voltdb.sysprocs.saverestore.SnapshotUtil;
import org.voltdb.utils.CompressionService;

import com.google.common.util.concurrent.Callables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;


public class DefaultSnapshotDataTarget implements SnapshotDataTarget {

    /*
     * Make it possible for test code to block a write and thus snapshot completion
     */
    public static volatile CountDownLatch m_simulateBlockedWrite = null;
    public static volatile boolean m_simulateFullDiskWritingHeader = false;
    public static volatile boolean m_simulateFullDiskWritingChunk = false;

    private final File m_file;
    private final FileChannel m_channel;
    private final FileOutputStream m_fos;
    private static final VoltLogger SNAP_LOG = new VoltLogger("SNAPSHOT");
    private Runnable m_onCloseHandler = null;

    /*
     * If a write fails then this snapshot is hosed.
     * Set the flag so all writes return immediately. The system still
     * needs to scan all the tables to clear the dirty bits
     * so the process continues as if the writes are succeeding.
     * A more efficient failure mode would do the scan but not the
     * extra serialization work.
     */
    private volatile boolean m_writeFailed = false;
    private volatile IOException m_writeException = null;

    private volatile long m_bytesWritten = 0;

    private static final Semaphore m_bytesAllowedBeforeSync = new Semaphore((1024 * 1024) * 256);
    private final AtomicInteger m_bytesWrittenSinceLastSync = new AtomicInteger(0);

    private final ScheduledFuture<?> m_syncTask;
    /*
     * Accept a single write even though simulating a full disk is enabled;
     */
    private volatile boolean m_acceptOneWrite = false;

    private boolean m_needsFinalClose = true;

    @SuppressWarnings("unused")
    private final String m_tableName;

    private final AtomicInteger m_outstandingWriteTasks = new AtomicInteger(0);
    private final ReentrantLock m_outstandingWriteTasksLock = new ReentrantLock();
    private final Condition m_noMoreOutstandingWriteTasksCondition =
            m_outstandingWriteTasksLock.newCondition();

    private static final ListeningExecutorService m_es = CoreUtils.getSingleThreadExecutor("Snapshot write service ");
    private static final ListeningScheduledExecutorService m_syncService = MoreExecutors.listeningDecorator(
            Executors.newSingleThreadScheduledExecutor(CoreUtils.getThreadFactory("Snapshot sync service")));

    public DefaultSnapshotDataTarget(
            final File file,
            final int hostId,
            final String clusterName,
            final String databaseName,
            final String tableName,
            final int numPartitions,
            final boolean isReplicated,
            final List<Integer> partitionIds,
            final VoltTable schemaTable,
            final long txnId,
            final long timestamp) throws IOException {
        this(
                file,
                hostId,
                clusterName,
                databaseName,
                tableName,
                numPartitions,
                isReplicated,
                partitionIds,
                schemaTable,
                txnId,
                timestamp,
                new int[] { 0, 0, 0, 2 });
    }

    public DefaultSnapshotDataTarget(
            final File file,
            final int hostId,
            final String clusterName,
            final String databaseName,
            final String tableName,
            final int numPartitions,
            final boolean isReplicated,
            final List<Integer> partitionIds,
            final VoltTable schemaTable,
            final long txnId,
            final long timestamp,
            int version[]
            ) throws IOException {
        String hostname = CoreUtils.getHostnameOrAddress();
        m_file = file;
        m_tableName = tableName;
        m_fos = new FileOutputStream(file);
        m_channel = m_fos.getChannel();
        m_needsFinalClose = !isReplicated;
        final FastSerializer fs = new FastSerializer();
        fs.writeInt(0);//CRC
        fs.writeInt(0);//Header length placeholder
        fs.writeByte(1);//Indicate the snapshot was not completed, set to true for the CRC calculation, false later
        for (int ii = 0; ii < 4; ii++) {
            fs.writeInt(version[ii]);//version
        }
        JSONStringer stringer = new JSONStringer();
        byte jsonBytes[] = null;
        try {
            stringer.object();
            stringer.key("txnId").value(txnId);
            stringer.key("hostId").value(hostId);
            stringer.key("hostname").value(hostname);
            stringer.key("clusterName").value(clusterName);
            stringer.key("databaseName").value(databaseName);
            stringer.key("tableName").value(tableName.toUpperCase());
            stringer.key("isReplicated").value(isReplicated);
            stringer.key("isCompressed").value(true);
            stringer.key("checksumType").value("CRC32C");
            stringer.key("timestamp").value(timestamp);
            /*
             * The timestamp string is for human consumption, automated stuff should use
             * the actual timestamp
             */
            stringer.key("timestampString").value(SnapshotUtil.formatHumanReadableDate(timestamp));
            if (!isReplicated) {
                stringer.key("partitionIds").array();
                for (int partitionId : partitionIds) {
                    stringer.value(partitionId);
                }
                stringer.endArray();

                stringer.key("numPartitions").value(numPartitions);
            }
            stringer.endObject();
            String jsonString = stringer.toString();
            JSONObject jsonObj = new JSONObject(jsonString);
            jsonString = jsonObj.toString(4);
            jsonBytes = jsonString.getBytes("UTF-8");
        } catch (Exception e) {
            throw new IOException(e);
        }
        fs.writeInt(jsonBytes.length);
        fs.write(jsonBytes);

        final BBContainer container = fs.getBBContainer();
        container.b.position(4);
        container.b.putInt(container.b.remaining() - 4);
        container.b.position(0);

        final byte schemaBytes[] = schemaTable.getSchemaBytes();

        final PureJavaCrc32 crc = new PureJavaCrc32();
        ByteBuffer aggregateBuffer = ByteBuffer.allocate(container.b.remaining() + schemaBytes.length);
        aggregateBuffer.put(container.b);
        aggregateBuffer.put(schemaBytes);
        aggregateBuffer.flip();
        crc.update(aggregateBuffer.array(), 4, aggregateBuffer.capacity() - 4);

        final int crcValue = (int) crc.getValue();
        aggregateBuffer.putInt(crcValue).position(8);
        aggregateBuffer.put((byte)0).position(0);//Haven't actually finished writing file

        if (m_simulateFullDiskWritingHeader) {
            m_writeException = new IOException("Disk full");
            m_writeFailed = true;
            m_fos.close();
            throw m_writeException;
        }

        /*
         * Be completely sure the write succeeded. If it didn't
         * the disk is probably full or the path is bunk etc.
         */
        m_acceptOneWrite = true;
        ListenableFuture<?> writeFuture =
                write(Callables.returning((BBContainer)DBBPool.wrapBB(aggregateBuffer)), false);
        try {
            writeFuture.get();
        } catch (InterruptedException e) {
            m_fos.close();
            throw new java.io.InterruptedIOException();
        } catch (ExecutionException e) {
            m_fos.close();
            throw m_writeException;
        }
        if (m_writeFailed) {
            m_fos.close();
            throw m_writeException;
        }

        ScheduledFuture<?> syncTask = null;
        syncTask = m_syncService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                int bytesSinceLastSync = 0;
                while ((bytesSinceLastSync = m_bytesWrittenSinceLastSync.getAndSet(0)) > 0) {
                    try {
                        m_channel.force(false);
                    } catch (IOException e) {
                        SNAP_LOG.error("Error syncing snapshot", e);
                    }
                    m_bytesAllowedBeforeSync.release(bytesSinceLastSync);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
        m_syncTask = syncTask;
    }

    @Override
    public boolean needsFinalClose()
    {
        return m_needsFinalClose;
    }

    @Override
    public void close() throws IOException, InterruptedException {
        try {
            m_outstandingWriteTasksLock.lock();
            try {
                while (m_outstandingWriteTasks.get() > 0) {
                    m_noMoreOutstandingWriteTasksCondition.await();
                }
            } finally {
                m_outstandingWriteTasksLock.unlock();
            }
            m_syncTask.cancel(false);
            m_channel.force(false);
        } finally {
            m_bytesAllowedBeforeSync.release(m_bytesWrittenSinceLastSync.getAndSet(0));
        }
        m_channel.position(8);
        ByteBuffer completed = ByteBuffer.allocate(1);
        if (m_writeFailed) {
            completed.put((byte)0).flip();
        } else {
            completed.put((byte)1).flip();
        }
        m_channel.write(completed);
        m_channel.force(false);
        m_channel.close();
        if (m_onCloseHandler != null) {
            m_onCloseHandler.run();
        }
    }

    @Override
    public int getHeaderSize() {
        return 0;
    }

    /*
     * Prepend length is basically synonymous with writing actual tuple data and not
     * the header.
     */
    private ListenableFuture<?> write(final Callable<BBContainer> tupleDataC, final boolean prependLength) {
        /*
         * Unwrap the data to be written. For the traditional
         * snapshot data target this should be a noop.
         */
        BBContainer tupleDataTemp;
        try {
            tupleDataTemp = tupleDataC.call();
            /*
             * Can be null if the dedupe filter nulled out the buffer
             */
            if (tupleDataTemp == null) {
                return Futures.immediateFuture(null);
            }
        } catch (Throwable t) {
            return Futures.immediateFailedFuture(t);
        }
        final BBContainer tupleData = tupleDataTemp;

        if (m_writeFailed) {
            tupleData.discard();
            return null;
        }

        m_outstandingWriteTasks.incrementAndGet();

        Future<BBContainer> compressionTask = null;
        if (prependLength) {
            BBContainer cont =
                    DBBPool.allocateDirectAndPool(
                            CompressionService.maxCompressedLength(SnapshotSiteProcessor.m_snapshotBufferLength));
            //Skip 4-bytes so the partition ID is not compressed
            //That way if we detect a corruption we know what partition is bad
            tupleData.b.position(tupleData.b.position() + 4);
            /*
             * Leave 12 bytes, it's going to be a 4-byte length prefix, a 4-byte partition id,
             * and a 4-byte CRC32C of just the header bytes, in addition to the compressed payload CRC
             * that is 16 bytes, but 4 of those are done by CompressionService
             */
            cont.b.position(12);
            compressionTask = CompressionService.compressAndCRC32cBufferAsync(tupleData.b, cont);
        }
        final Future<BBContainer> compressionTaskFinal = compressionTask;

        ListenableFuture<?> writeTask = m_es.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                try {
                    if (m_acceptOneWrite) {
                        m_acceptOneWrite = false;
                    } else {
                        if (m_simulateBlockedWrite != null) {
                            m_simulateBlockedWrite.await();
                        }
                        if (m_simulateFullDiskWritingChunk) {
                            throw new IOException("Disk full");
                        }
                    }

                    int totalWritten = 0;
                    if (prependLength) {
                        BBContainer payloadContainer = compressionTaskFinal.get();
                        try {
                            final ByteBuffer payloadBuffer = payloadContainer.b;
                            payloadBuffer.position(0);

                            ByteBuffer lengthPrefix = ByteBuffer.allocate(12);
                            m_bytesAllowedBeforeSync.acquire(payloadBuffer.remaining());
                            //Length prefix does not include 4 header items, just compressd payload
                            //that follows
                            lengthPrefix.putInt(payloadBuffer.remaining() - 16);//length prefix
                            lengthPrefix.putInt(tupleData.b.getInt(0)); // partitionId

                            /*
                             * Checksum the header and put it in the payload buffer
                             */
                            PureJavaCrc32C crc = new PureJavaCrc32C();
                            crc.update(lengthPrefix.array(), 0, 8);
                            lengthPrefix.putInt((int)crc.getValue());
                            lengthPrefix.flip();
                            payloadBuffer.put(lengthPrefix);
                            payloadBuffer.position(0);

                            /*
                             * Write payload to file
                             */
                            while (payloadBuffer.hasRemaining()) {
                                totalWritten += m_channel.write(payloadBuffer);
                            }
                        } finally {
                            payloadContainer.discard();
                        }
                    } else {
                        while (tupleData.b.hasRemaining()) {
                            totalWritten += m_channel.write(tupleData.b);
                        }
                    }
                    m_bytesWritten += totalWritten;
                    m_bytesWrittenSinceLastSync.addAndGet(totalWritten);
                } catch (IOException e) {
                    m_writeException = e;
                    SNAP_LOG.error("Error while attempting to write snapshot data to file " + m_file, e);
                    m_writeFailed = true;
                    throw e;
                } finally {
                    try {
                        tupleData.discard();
                    } finally {
                        m_outstandingWriteTasksLock.lock();
                        try {
                            if (m_outstandingWriteTasks.decrementAndGet() == 0) {
                                m_noMoreOutstandingWriteTasksCondition.signalAll();
                            }
                        } finally {
                            m_outstandingWriteTasksLock.unlock();
                        }
                    }
                }
                return null;
            }
        });
        return writeTask;
    }

    @Override
    public ListenableFuture<?> write(final Callable<BBContainer> tupleData, SnapshotTableTask context) {
        return write(tupleData, true);
    }

    @Override
    public long getBytesWritten() {
        return m_bytesWritten;
    }

    @Override
    public void setOnCloseHandler(Runnable onClose) {
        m_onCloseHandler = onClose;
    }

    @Override
    public IOException getLastWriteException() {
        return m_writeException;
    }

    @Override
    public SnapshotFormat getFormat() {
        return SnapshotFormat.NATIVE;
    }

    @Override
    public String toString() {
        return m_file.toString();
    }
}
