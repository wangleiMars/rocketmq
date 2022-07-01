/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.config.StorePathConfigHelper;

/**
 * 消息消费队列，引入的目的主要是提高消息消费的性能，由于RocketMQ是基于主题topic的订阅模式，消息消费是针对主题进行的，
 * 如果要遍历commitlog文件中根据topic检索消息是非常低效的。
 * Consumer即可根据ConsumeQueue来查找待消费的消息。其中，ConsumeQueue（逻辑消费队列）作为消费消息的索引，
 * 保存了指定Topic下的队列消息在CommitLog中的起始物理偏移量offset，消息大小size和消息Tag的HashCode值。
 * consumequeue文件可以看成是基于topic的commitlog索引文件，故consumequeue文件夹的组织方式如下：
 * topic/queue/file三层组织结构，具体存储路径为：$HOME/store/consumequeue/{topic}/{queueId}/{fileName}。
 * 同样consumequeue文件采取定长设计，每一个条目共20个字节，分别为8字节的commitlog物理偏移量、
 * 4字节的消息长度、8字节tag hashcode，单个文件由30W个条目组成，可以像数组一样随机访问每一个条目，每个ConsumeQueue文件大小约5.72M；
 */
public class ConsumeQueue {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    public static final int CQ_STORE_UNIT_SIZE = 20;
    private static final InternalLogger LOG_ERROR = InternalLoggerFactory.getLogger(LoggerName.STORE_ERROR_LOGGER_NAME);

    private final DefaultMessageStore defaultMessageStore;
    //映射文件队列
    private final MappedFileQueue mappedFileQueue;
    //消息的Topic
    private final String topic;
    private final int queueId;
    //指定大小的缓冲，因为一个记录的大小是20byte的固定大小
    private final ByteBuffer byteBufferIndex;
    //保存的路径
    private final String storePath;
    //映射文件的大小
    private final int mappedFileSize;
    //最后一个消息对应的物理偏移量  也就是在CommitLog中的偏移量
    private long maxPhysicOffset = -1;
    //最小的逻辑偏移量 在ConsumeQueue中的最小偏移量
    private volatile long minLogicOffset = 0;
    //ConsumeQueue的扩展文件，保存一些不重要的信息，比如消息存储时间等
    private ConsumeQueueExt consumeQueueExt = null;

    public ConsumeQueue(
        final String topic,
        final int queueId,
        final String storePath,
        final int mappedFileSize,
        final DefaultMessageStore defaultMessageStore) {
        this.storePath = storePath;
        this.mappedFileSize = mappedFileSize;
        this.defaultMessageStore = defaultMessageStore;

        this.topic = topic;
        this.queueId = queueId;
        //设置对应的文件路径，$HOME/store/consumequeue/{topic}/{queueId}/{fileName}
        String queueDir = this.storePath
            + File.separator + topic
            + File.separator + queueId;
        //创建文件映射队列
        this.mappedFileQueue = new MappedFileQueue(queueDir, mappedFileSize, null);
        //创建20个字节大小的缓冲
        this.byteBufferIndex = ByteBuffer.allocate(CQ_STORE_UNIT_SIZE);
        //是否启用消息队列的扩展存储
        if (defaultMessageStore.getMessageStoreConfig().isEnableConsumeQueueExt()) {
            //创建一个扩展存储对象
            this.consumeQueueExt = new ConsumeQueueExt(
                topic,
                queueId,
                StorePathConfigHelper.getStorePathConsumeQueueExt(defaultMessageStore.getMessageStoreConfig().getStorePathRootDir()),
                defaultMessageStore.getMessageStoreConfig().getMappedFileSizeConsumeQueueExt(),
                defaultMessageStore.getMessageStoreConfig().getBitMapLengthConsumeQueueExt()
            );
        }
    }

    public boolean load() {
        //从映射文件队列加载
        boolean result = this.mappedFileQueue.load();
        log.info("load consume queue " + this.topic + "-" + this.queueId + " " + (result ? "OK" : "Failed"));
        //存在扩展存储则加载
        if (isExtReadEnable()) {
            //消息队列扩展加载
            result &= this.consumeQueueExt.load();
        }
        return result;
    }

    /**
     * 服务重启时修复文件
     */
    public void recover() {
        final List<MappedFile> mappedFiles = this.mappedFileQueue.getMappedFiles();
        if (!mappedFiles.isEmpty()) {
            //如果文件列表大于3就从倒数第3个开始，否则从第一个开始
            int index = mappedFiles.size() - 3;
            if (index < 0)
                index = 0;
            //获取consumeQueue单个文件的大小
            int mappedFileSizeLogics = this.mappedFileSize;
            //获取最后一个映射文件
            MappedFile mappedFile = mappedFiles.get(index);
            ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
            //映射文件处理的起始偏移量
            long processOffset = mappedFile.getFileFromOffset();
            long mappedFileOffset = 0;
            long maxExtAddr = 1;
            while (true) {
                for (int i = 0; i < mappedFileSizeLogics; i += CQ_STORE_UNIT_SIZE) {
                    long offset = byteBuffer.getLong();
                    int size = byteBuffer.getInt();
                    long tagsCode = byteBuffer.getLong();
                    //顺序解析，每个数据单元隔20个字节,如果offset跟size大于0则表示有效
                    if (offset >= 0 && size > 0) {
                        //正常数据的大小
                        mappedFileOffset = i + CQ_STORE_UNIT_SIZE;
                        //设置最大的物理偏移量
                        this.maxPhysicOffset = offset + size;
                        if (isExtAddr(tagsCode)) {
                            maxExtAddr = tagsCode;
                        }
                    } else {
                        log.info("recover current consume queue file over,  " + mappedFile.getFileName() + " "
                            + offset + " " + size + " " + tagsCode);
                        break;
                    }
                }
                //如果已经   加载正常数据的大小 = 队列文件的大小，则表示这个文件加载完毕
                if (mappedFileOffset == mappedFileSizeLogics) {
                    index++;
                    if (index >= mappedFiles.size()) {

                        log.info("recover last consume queue file over, last mapped file "
                            + mappedFile.getFileName());
                        break;
                    } else {
                        mappedFile = mappedFiles.get(index);
                        byteBuffer = mappedFile.sliceByteBuffer();
                        processOffset = mappedFile.getFileFromOffset();
                        mappedFileOffset = 0;
                        log.info("recover next consume queue file, " + mappedFile.getFileName());
                    }
                } else {
                    log.info("recover current consume queue queue over " + mappedFile.getFileName() + " "
                        + (processOffset + mappedFileOffset));
                    break;
                }
            }
            // 完整的偏移量 = 最后一个文件的起始偏移量(getFileFromOffset) +  正常数据的长度(mappedFileOffset)
            processOffset += mappedFileOffset;
            //设置刷新到的 offset位置
            this.mappedFileQueue.setFlushedWhere(processOffset);
            this.mappedFileQueue.setCommittedWhere(processOffset);
            this.mappedFileQueue.truncateDirtyFiles(processOffset);
            //如果有扩展文件，则恢复扩展文件
            if (isExtReadEnable()) {
                this.consumeQueueExt.recover();
                log.info("Truncate consume queue extend file by max {}", maxExtAddr);
                this.consumeQueueExt.truncateByMaxAddress(maxExtAddr);
            }
        }
    }

    /**
     * 根据时间获取消息在队列中的逻辑位置
     * @param timestamp
     * @return
     */
    public long getOffsetInQueueByTime(final long timestamp) {
        //根据时间找到映射的文件，文件可以知道最后一次修改的时间
        MappedFile mappedFile = this.mappedFileQueue.getMappedFileByTime(timestamp);
        if (mappedFile != null) {
            long offset = 0;
            //如果文件的最小偏移量 大于 查找的时间戳所在的文件的起始偏移量 说明对应的消息在这个文件中。
            int low = minLogicOffset > mappedFile.getFileFromOffset() ? (int) (minLogicOffset - mappedFile.getFileFromOffset()) : 0;
            int high = 0;
            int midOffset = -1, targetOffset = -1, leftOffset = -1, rightOffset = -1;
            long leftIndexValue = -1L, rightIndexValue = -1L;
            //获取最小的物理偏移量  也就是CommitLog的最小偏移量
            long minPhysicOffset = this.defaultMessageStore.getMinPhyOffset();
            //获取文件的内容buffer
            SelectMappedBufferResult sbr = mappedFile.selectMappedBuffer(0);
            if (null != sbr) {
                ByteBuffer byteBuffer = sbr.getByteBuffer();
                //计算文件的最大的数据单元的偏移量
                high = byteBuffer.limit() - CQ_STORE_UNIT_SIZE;
                try {
                    //用二分法来获取更新的时间戳
                    while (high >= low) {
                        //获取中间单元
                        midOffset = (low + high) / (2 * CQ_STORE_UNIT_SIZE) * CQ_STORE_UNIT_SIZE;
                        byteBuffer.position(midOffset);
                        //获取消息的物理偏移量，也就是在commitLog上的偏移量
                        long phyOffset = byteBuffer.getLong();
                        int size = byteBuffer.getInt();
                        //如果小于最小的物理偏移量，则取下一条消息的位置
                        if (phyOffset < minPhysicOffset) {
                            low = midOffset + CQ_STORE_UNIT_SIZE;
                            leftOffset = midOffset;
                            continue;
                        }
                        //按物理offset从commitLog中获取存储时间=》
                        long storeTime =
                            this.defaultMessageStore.getCommitLog().pickupStoreTimestamp(phyOffset, size);
                        if (storeTime < 0) {
                            return 0;
                        } else if (storeTime == timestamp) {//如果存储时间相等就是要找的
                            targetOffset = midOffset;
                            break;
                        } else if (storeTime > timestamp) {//如果存储时间大于目标时间，则消息需要往前找
                            high = midOffset - CQ_STORE_UNIT_SIZE;
                            rightOffset = midOffset;
                            rightIndexValue = storeTime;
                        } else {//如果存储时间小于目标时间，则消息需要往后
                            low = midOffset + CQ_STORE_UNIT_SIZE;
                            leftOffset = midOffset;
                            leftIndexValue = storeTime;
                        }
                    }
                    //找到了符合条件的消息的逻辑地址
                    if (targetOffset != -1) {

                        offset = targetOffset;
                    } else {
                        if (leftIndexValue == -1) {

                            offset = rightOffset;
                        } else if (rightIndexValue == -1) {

                            offset = leftOffset;
                        } else {
                            offset =
                                Math.abs(timestamp - leftIndexValue) > Math.abs(timestamp
                                    - rightIndexValue) ? rightOffset : leftOffset;
                        }
                    }
                    //返回对应的消息
                    return (mappedFile.getFileFromOffset() + offset) / CQ_STORE_UNIT_SIZE;
                } finally {
                    // 映射文件释放
                    sbr.release();
                }
            }
        }
        return 0;
    }

    /**
     * 截断逻辑文件truncateDirtyLogicFiles
     * 截断文件，一般在服务重启后会调用，用来删除损坏或者有问题的消息。主要的逻辑步骤如下：
     *
     * 遍历MappedFile，遍历文件中的消息单元，如果是第一个消息单元，则比较消息单元中记录的物理偏移量是不是大于传入的phyOffet，如果是的则删除当前文件。
     * 依次比较每个单元记录的偏移量和phyOffet大小，直到大于phyOffet值，然后重置文件的提交，写入和刷新的文职
     * @param phyOffet
     */
    public void truncateDirtyLogicFiles(long phyOffet) {

        int logicFileSize = this.mappedFileSize;

        this.maxPhysicOffset = phyOffet;
        long maxExtAddr = 1;
        while (true) {
            // 获取映射队列中最后的映射文件
            MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile();
            if (mappedFile != null) {
                ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();

                mappedFile.setWrotePosition(0);
                mappedFile.setCommittedPosition(0);
                mappedFile.setFlushedPosition(0);
                //遍历所有的MappedFile，每次读取的间隔是20个字节（ConsumeQueue的单个数据单元大小为20字节）
                for (int i = 0; i < logicFileSize; i += CQ_STORE_UNIT_SIZE) {
                    long offset = byteBuffer.getLong();
                    int size = byteBuffer.getInt();
                    long tagsCode = byteBuffer.getLong();

                    if (0 == i) {
                        //如果第一个单元的物理偏移量CommitLog Offset大于phyOffet，则直接删除最后一个文件。因为phyOffet表示的是最后一个有效的commitLog文件的起始偏移量。
                        if (offset >= phyOffet) {
                            this.mappedFileQueue.deleteLastMappedFile();
                            break;
                        } else {
                            //设置wrotePostion和CommittedPosition两个变量为解析到的数据块位置
                            int pos = i + CQ_STORE_UNIT_SIZE;
                            mappedFile.setWrotePosition(pos);
                            mappedFile.setCommittedPosition(pos);
                            mappedFile.setFlushedPosition(pos);
                            this.maxPhysicOffset = offset + size;
                            // This maybe not take effect, when not every consume queue has extend file.
                            if (isExtAddr(tagsCode)) {
                                maxExtAddr = tagsCode;
                            }
                        }
                    } else {

                        //解析到数据块的大小为空或者物理偏移值大于了processOffset为止。
                        if (offset >= 0 && size > 0) {

                            if (offset >= phyOffet) {
                                return;
                            }

                            int pos = i + CQ_STORE_UNIT_SIZE;
                            mappedFile.setWrotePosition(pos);
                            mappedFile.setCommittedPosition(pos);
                            mappedFile.setFlushedPosition(pos);
                            this.maxPhysicOffset = offset + size;
                            if (isExtAddr(tagsCode)) {
                                maxExtAddr = tagsCode;
                            }

                            if (pos == logicFileSize) {
                                return;
                            }
                        } else {
                            return;
                        }
                    }
                }
            } else {
                break;
            }
        }

        if (isExtReadEnable()) {
            //            删除最大位置的消息队列=
            this.consumeQueueExt.truncateByMaxAddress(maxExtAddr);
        }
    }

    public long getLastOffset() {
        long lastOffset = -1;

        int logicFileSize = this.mappedFileSize;

        MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile();
        if (mappedFile != null) {

            int position = mappedFile.getWrotePosition() - CQ_STORE_UNIT_SIZE;
            if (position < 0)
                position = 0;

            ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
            byteBuffer.position(position);
            for (int i = 0; i < logicFileSize; i += CQ_STORE_UNIT_SIZE) {
                long offset = byteBuffer.getLong();
                int size = byteBuffer.getInt();
                byteBuffer.getLong();

                if (offset >= 0 && size > 0) {
                    lastOffset = offset + size;
                } else {
                    break;
                }
            }
        }

        return lastOffset;
    }

    public boolean flush(final int flushLeastPages) {
        boolean result = this.mappedFileQueue.flush(flushLeastPages);
        if (isExtReadEnable()) {
            result = result & this.consumeQueueExt.flush(flushLeastPages);
        }

        return result;
    }

    public int deleteExpiredFile(long offset) {
        int cnt = this.mappedFileQueue.deleteExpiredFileByOffset(offset, CQ_STORE_UNIT_SIZE);
        this.correctMinOffset(offset);
        return cnt;
    }

    public void correctMinOffset(long phyMinOffset) {
        MappedFile mappedFile = this.mappedFileQueue.getFirstMappedFile();
        long minExtAddr = 1;
        if (mappedFile != null) {
            SelectMappedBufferResult result = mappedFile.selectMappedBuffer(0);
            if (result != null) {
                try {
                    for (int i = 0; i < result.getSize(); i += ConsumeQueue.CQ_STORE_UNIT_SIZE) {
                        long offsetPy = result.getByteBuffer().getLong();
                        result.getByteBuffer().getInt();
                        long tagsCode = result.getByteBuffer().getLong();

                        if (offsetPy >= phyMinOffset) {
                            this.minLogicOffset = mappedFile.getFileFromOffset() + i;
                            log.info("Compute logical min offset: {}, topic: {}, queueId: {}",
                                this.getMinOffsetInQueue(), this.topic, this.queueId);
                            // This maybe not take effect, when not every consume queue has extend file.
                            if (isExtAddr(tagsCode)) {
                                minExtAddr = tagsCode;
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.error("Exception thrown when correctMinOffset", e);
                } finally {
                    result.release();
                }
            }
        }

        if (isExtReadEnable()) {
            this.consumeQueueExt.truncateByMinAddress(minExtAddr);
        }
    }

    public long getMinOffsetInQueue() {
        return this.minLogicOffset / CQ_STORE_UNIT_SIZE;
    }

    /**
     * 保存消息逻辑日志putMessagePositionInfoWrapper
     * 逻辑消息的保存逻辑比较长，主要的逻辑步骤如下：
     *
     * 检查对应的文件是不是可写的状态，以及写入的重试次数是否达到上限30次
     * 如果消息扩展服务开启了，则保存对应的扩展信息到扩展文件队列中
     * 组装消息进行写入
     * 如果写入成功，则更新CheckPoint文件中的逻辑日志落盘时间
     * 其中组装消息写入被抽出到另外一个方法putMessagePositionInfo中。主要逻辑如下：
    。
     */
    public void putMessagePositionInfoWrapper(DispatchRequest request, boolean multiQueue) {
        //最大的重试次数
        final int maxRetries = 30;
        //检查对应的ConsumeQueue文件是不是可写
        boolean canWrite = this.defaultMessageStore.getRunningFlags().isCQWriteable();
        //可写 并且 重试次数还没达到30次，则进行写入
        for (int i = 0; i < maxRetries && canWrite; i++) {
            //获取消息的  Tag
            long tagsCode = request.getTagsCode();
            //消息扩展服务是否开启
            if (isExtWriteEnable()) {
                ConsumeQueueExt.CqExtUnit cqExtUnit = new ConsumeQueueExt.CqExtUnit();
                cqExtUnit.setFilterBitMap(request.getBitMap());
                cqExtUnit.setMsgStoreTime(request.getStoreTimestamp());
                cqExtUnit.setTagsCode(request.getTagsCode());

                long extAddr = this.consumeQueueExt.put(cqExtUnit);
                if (isExtAddr(extAddr)) {
                    tagsCode = extAddr;
                } else {
                    log.warn("Save consume queue extend fail, So just save tagsCode! {}, topic:{}, queueId:{}, offset:{}", cqExtUnit,
                        topic, queueId, request.getCommitLogOffset());
                }
            }
            //组装消息存储位置信息=》CommitLog中的偏移量，消息的大小，和小的Tag的hash值
            boolean result = this.putMessagePositionInfo(request.getCommitLogOffset(),
                request.getMsgSize(), tagsCode, request.getConsumeQueueOffset());
            //设置CheckPoint文件中的逻辑日志落盘时间
            if (result) {
                if (this.defaultMessageStore.getMessageStoreConfig().getBrokerRole() == BrokerRole.SLAVE ||
                    this.defaultMessageStore.getMessageStoreConfig().isEnableDLegerCommitLog()) {
                    this.defaultMessageStore.getStoreCheckpoint().setPhysicMsgTimestamp(request.getStoreTimestamp());
                }
                this.defaultMessageStore.getStoreCheckpoint().setLogicsMsgTimestamp(request.getStoreTimestamp());
                if (multiQueue) {
                    multiDispatchLmqQueue(request, maxRetries);
                }
                return;
            } else {
                // XXX: warn and notify me
                log.warn("[BUG]put commit log position info to " + topic + ":" + queueId + " " + request.getCommitLogOffset()
                    + " failed, retry " + i + " times");

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.warn("", e);
                }
            }
        }

        // XXX: warn and notify me
        log.error("[BUG]consume queue can not write, {} {}", this.topic, this.queueId);
        this.defaultMessageStore.getRunningFlags().makeLogicsQueueError();
    }

    private void multiDispatchLmqQueue(DispatchRequest request, int maxRetries) {
        Map<String, String> prop = request.getPropertiesMap();
        String multiDispatchQueue = prop.get(MessageConst.PROPERTY_INNER_MULTI_DISPATCH);
        String multiQueueOffset = prop.get(MessageConst.PROPERTY_INNER_MULTI_QUEUE_OFFSET);
        String[] queues = multiDispatchQueue.split(MixAll.MULTI_DISPATCH_QUEUE_SPLITTER);
        String[] queueOffsets = multiQueueOffset.split(MixAll.MULTI_DISPATCH_QUEUE_SPLITTER);
        if (queues.length != queueOffsets.length) {
            log.error("[bug] queues.length!=queueOffsets.length ", request.getTopic());
            return;
        }
        for (int i = 0; i < queues.length; i++) {
            String queueName = queues[i];
            long queueOffset = Long.parseLong(queueOffsets[i]);
            int queueId = request.getQueueId();
            if (this.defaultMessageStore.getMessageStoreConfig().isEnableLmq() && MixAll.isLmq(queueName)) {
                queueId = 0;
            }
            doDispatchLmqQueue(request, maxRetries, queueName, queueOffset, queueId);

        }
        return;
    }

    private void doDispatchLmqQueue(DispatchRequest request, int maxRetries, String queueName, long queueOffset,
        int queueId) {
        ConsumeQueue cq = this.defaultMessageStore.findConsumeQueue(queueName, queueId);
        boolean canWrite = this.defaultMessageStore.getRunningFlags().isCQWriteable();
        for (int i = 0; i < maxRetries && canWrite; i++) {
            boolean result = cq.putMessagePositionInfo(request.getCommitLogOffset(), request.getMsgSize(),
                request.getTagsCode(),
                queueOffset);
            if (result) {
                break;
            } else {
                log.warn("[BUG]put commit log position info to " + queueName + ":" + queueId + " " + request.getCommitLogOffset()
                    + " failed, retry " + i + " times");

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.warn("", e);
                }
            }
        }
    }

    /**
     *   申请20个字节长度的buffer，然后依次拼接消息在CommitLog中的偏移量，消息长度和消息的tagCode
     *   然后获取 MappedFile，并把消息保存进去，同时更新maxPhysicOffset字段
     * @param offset
     * @param size
     * @param tagsCode
     * @param cqOffset
     * @return
     */
    private boolean putMessagePositionInfo(final long offset, /*CommitLog文件的偏移量*/
                                           final int size,/*消息的大小*/
                                           final long tagsCode,/*消息的tag*/
        final long cqOffset/*ConsumeQueue的偏移，这个偏移在添加消息到CommitLog的时候确定了*/) {
        //如果CommitLog 的偏移量比consumequeue的最大偏移量还小，说明已经追加过了
        if (offset + size <= this.maxPhysicOffset) {
            log.warn("Maybe try to build consume queue repeatedly maxPhysicOffset={} phyOffset={}", maxPhysicOffset, offset);
            return true;
        }
        //把buffer重置
        this.byteBufferIndex.flip();
        //申请20个字节大小
        this.byteBufferIndex.limit(CQ_STORE_UNIT_SIZE);
        //设置在CommitLog中的偏移量
        this.byteBufferIndex.putLong(offset);
        //设置消息的大小
        this.byteBufferIndex.putInt(size);
        //设置消息的tag信息
        this.byteBufferIndex.putLong(tagsCode);
        //希望拼接到的偏移量=commitLog中的QUEUEOFFSET*20
        final long expectLogicOffset = cqOffset * CQ_STORE_UNIT_SIZE;
        //从映射文件队列中获取最后一个映射文件=》
        MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile(expectLogicOffset);
        if (mappedFile != null) {
            //映射文是第一个创建、consumerOffset不是0，映射文件写位置是0
            if (mappedFile.isFirstCreateInQueue() && cqOffset != 0 && mappedFile.getWrotePosition() == 0) {
                //设置最小的逻辑偏移量 为 对应消息的起始偏移量
                this.minLogicOffset = expectLogicOffset;
                this.mappedFileQueue.setFlushedWhere(expectLogicOffset);
                this.mappedFileQueue.setCommittedWhere(expectLogicOffset);
                //填充文件=》如果不是第一次拼接，则指定位置进行拼接
                this.fillPreBlank(mappedFile, expectLogicOffset);
                log.info("fill pre blank space " + mappedFile.getFileName() + " " + expectLogicOffset + " "
                    + mappedFile.getWrotePosition());
            }
            //consumerOffset不是0，则表示不是文件中的第一个记录
            if (cqOffset != 0) {
                //计算当前的文件提交到的位置
                long currentLogicOffset = mappedFile.getWrotePosition() + mappedFile.getFileFromOffset();
                //如果文件写入的偏移量 已经 大于这个消息写入的初始偏移量， 表示这个消息重复了
                if (expectLogicOffset < currentLogicOffset) {
                    log.warn("Build  consume queue repeatedly, expectLogicOffset: {} currentLogicOffset: {} Topic: {} QID: {} Diff: {}",
                        expectLogicOffset, currentLogicOffset, this.topic, this.queueId, expectLogicOffset - currentLogicOffset);
                    return true;
                }
                //如果两个值不相等， 说明队列的顺序可能有问题
                if (expectLogicOffset != currentLogicOffset) {
                    LOG_ERROR.warn(
                        "[BUG]logic queue order maybe wrong, expectLogicOffset: {} currentLogicOffset: {} Topic: {} QID: {} Diff: {}",
                        expectLogicOffset,
                        currentLogicOffset,
                        this.topic,
                        this.queueId,
                        expectLogicOffset - currentLogicOffset
                    );
                }
            }
            //这只最大的物理偏移量为CommitLog 的偏移量
            this.maxPhysicOffset = offset + size;
            //消息写入映射文件=》
            return mappedFile.appendMessage(this.byteBufferIndex.array());
        }
        return false;
    }

    private void fillPreBlank(final MappedFile mappedFile, final long untilWhere) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(CQ_STORE_UNIT_SIZE);
        byteBuffer.putLong(0L);
        byteBuffer.putInt(Integer.MAX_VALUE);
        byteBuffer.putLong(0L);

        int until = (int) (untilWhere % this.mappedFileQueue.getMappedFileSize());
        for (int i = 0; i < until; i += CQ_STORE_UNIT_SIZE) {
            mappedFile.appendMessage(byteBuffer.array());
        }
    }

    /**
     * 根据消息的index获取消息单元
     * @param startIndex
     * @return
     */
    public SelectMappedBufferResult getIndexBuffer(final long startIndex) {
        int mappedFileSize = this.mappedFileSize;
        //计算消息的在文件中的便宜offset
        long offset = startIndex * CQ_STORE_UNIT_SIZE;
        //偏移量小于最小的逻辑偏移量，则说明消息在文件中
        if (offset >= this.getMinLogicOffset()) {
            //根据offset查询映射文件
            MappedFile mappedFile = this.mappedFileQueue.findMappedFileByOffset(offset);
            if (mappedFile != null) {
                SelectMappedBufferResult result = mappedFile.selectMappedBuffer((int) (offset % mappedFileSize));
                return result;
            }
        }
        return null;
    }

    public ConsumeQueueExt.CqExtUnit getExt(final long offset) {
        if (isExtReadEnable()) {
            return this.consumeQueueExt.get(offset);
        }
        return null;
    }

    public boolean getExt(final long offset, ConsumeQueueExt.CqExtUnit cqExtUnit) {
        if (isExtReadEnable()) {
            return this.consumeQueueExt.get(offset, cqExtUnit);
        }
        return false;
    }

    public long getMinLogicOffset() {
        return minLogicOffset;
    }

    public void setMinLogicOffset(long minLogicOffset) {
        this.minLogicOffset = minLogicOffset;
    }

    public long rollNextFile(final long index) {
        int mappedFileSize = this.mappedFileSize;
        int totalUnitsInFile = mappedFileSize / CQ_STORE_UNIT_SIZE;
        return index + totalUnitsInFile - index % totalUnitsInFile;
    }

    public String getTopic() {
        return topic;
    }

    public int getQueueId() {
        return queueId;
    }

    public long getMaxPhysicOffset() {
        return maxPhysicOffset;
    }

    public void setMaxPhysicOffset(long maxPhysicOffset) {
        this.maxPhysicOffset = maxPhysicOffset;
    }

    public void destroy() {
        this.maxPhysicOffset = -1;
        this.minLogicOffset = 0;
        this.mappedFileQueue.destroy();
        if (isExtReadEnable()) {
            this.consumeQueueExt.destroy();
        }
    }

    public long getMessageTotalInQueue() {
        return this.getMaxOffsetInQueue() - this.getMinOffsetInQueue();
    }

    public long getMaxOffsetInQueue() {
        return this.mappedFileQueue.getMaxOffset() / CQ_STORE_UNIT_SIZE;
    }

    public void checkSelf() {
        mappedFileQueue.checkSelf();
        if (isExtReadEnable()) {
            this.consumeQueueExt.checkSelf();
        }
    }

    protected boolean isExtReadEnable() {
        return this.consumeQueueExt != null;
    }

    protected boolean isExtWriteEnable() {
        return this.consumeQueueExt != null
            && this.defaultMessageStore.getMessageStoreConfig().isEnableConsumeQueueExt();
    }

    /**
     * Check {@code tagsCode} is address of extend file or tags code.
     */
    public boolean isExtAddr(long tagsCode) {
        return ConsumeQueueExt.isExtAddr(tagsCode);
    }

}
