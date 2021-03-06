/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 * This file is part of the aion network project.
 *
 * The aion network project is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * The aion network project is distributed in the hope that it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the aion network project source files.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * The aion network project leverages useful source code from other
 * open source projects. We greatly appreciate the effort that was
 * invested in these projects and we thank the individual contributors
 * for their work. For provenance information and contributors
 * please see <https://github.com/aionnetwork/aion/wiki/Contributors>.
 *
 * Contributors to the aion source files in decreasing order of code volume:
 * Aion foundation.
 */

package org.aion.zero.impl.sync;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.aion.base.util.Hex;
import org.apache.commons.collections4.map.LRUMap;
import org.slf4j.Logger;
import org.aion.base.util.ByteArrayWrapper;
import org.aion.mcf.blockchain.IChainCfg;
import org.aion.mcf.core.ImportResult;
import org.aion.evtmgr.IEvent;
import org.aion.evtmgr.IEventMgr;
import org.aion.evtmgr.impl.evt.EventConsensus;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.p2p.INode;
import org.aion.p2p.IP2pMgr;
import org.aion.zero.impl.AionBlockchainImpl;
import org.aion.zero.impl.blockchain.ChainConfiguration;
import org.aion.zero.impl.sync.msg.ReqBlocksBodies;
import org.aion.zero.impl.sync.msg.ReqBlocksHeaders;
import org.aion.zero.impl.sync.msg.ReqStatus;
import org.aion.zero.impl.types.AionBlock;
import org.aion.zero.types.A0BlockHeader;
import org.aion.mcf.valid.BlockHeaderValidator;

/**
 * @author chris
 * TODO: pre selected peers list based on target total difficult
 */
public final class SyncMgr {

    // interval time get peer status
    private static final int STATUS_INTERVAL = 2000;

    // timeout sent headers
    private static final int SENT_HEADERS_TIMEOUT = 5000;

    private final static Logger LOG = AionLoggerFactory.getLogger(LogEnum.SYNC.name());

    // re-use same req status
    private final static ReqStatus reqStatus = new ReqStatus();

    // default how many blocks forward to sync based on current block number
    private int syncForwardMax = 192;

    private int blocksQueueMax = 2000;

    private AionBlockchainImpl blockchain;
    private IP2pMgr p2pMgr;
    private IEventMgr evtMgr;
    private BlockHeaderValidator blockHeaderValidator;
    private AtomicBoolean start = new AtomicBoolean(true);

    // set as last block number within one batch import when first block
    // imported success as best
    // reset to 0 as any block import result as no parent (side chain)
    private AtomicLong jump = new AtomicLong(0);

    class NetBestStatus {
        // network best block number for self node perspective
        long blockNumber = 0L;

        // network best block hash for self node perspective
        byte[] blockHash = new byte[0];

        BigInteger totalDiff = BigInteger.ZERO;

    }

    private AtomicReference<NetBestStatus> netBestStatus = new AtomicReference<>(new NetBestStatus());

    // store headers that has been sent to fetch block bodies
    private final ConcurrentHashMap<Integer, HeadersWrapper> sentHeaders = new ConcurrentHashMap<>();

    // store validated headers from network
    private final BlockingQueue<HeadersWrapper> importedHeaders = new LinkedBlockingQueue<>();

    // store blocks that ready to save to db
    private final BlockingQueue<AionBlock> importedBlocks = new LinkedBlockingQueue<>();

    // filter based on imported blocks
    private final Map<ByteArrayWrapper, Object> savedHashes = Collections.synchronizedMap(new LRUMap<>(1024));

    private ScheduledThreadPoolExecutor scheduledWorkers;

    private static final class AionSyncMgrHolder {
        static final SyncMgr INSTANCE = new SyncMgr();
    }

    public static SyncMgr inst() {
        return AionSyncMgrHolder.INSTANCE;
    }

    // Attation:
    // update best block is handler function from p2p thread pool. even the
    // blocknumber and blockhash is atomic, but still need sync to prevent
    // blocknumber link to wrong block hash.
    public synchronized void updateNetworkBestBlock(String _displayId, long _nodeBestBlockNumber,
            final byte[] _nodeBestBlockHash, final byte[] _totalDiff) {
        long selfBestBlockNumber = this.blockchain.getBestBlock().getNumber();
        BigInteger totalDiff = new BigInteger(1, _totalDiff);
        if (_nodeBestBlockNumber > this.netBestStatus.get().blockNumber) {
            if (netBestStatus.get().totalDiff.compareTo(totalDiff) < 0) {
                netBestStatus.get().blockNumber = _nodeBestBlockNumber;
                netBestStatus.get().blockHash = _nodeBestBlockHash;
                netBestStatus.get().totalDiff = totalDiff;
                if (_nodeBestBlockNumber > this.blockchain.getBestBlock().getNumber())
                    getHeaders();

            } else {

                if (LOG.isDebugEnabled()) {
                    LOG.debug(
                            "<network-best-block-diff-fail remote-id={} num={} diff={} best-diff={} self-num={}  known-network-num={} send-on-sync-done>",
                            _displayId, _nodeBestBlockNumber, totalDiff, netBestStatus.get().totalDiff,
                            selfBestBlockNumber, this.netBestStatus.get().blockNumber);
                }
            }
        }

        if (this.netBestStatus.get().blockNumber <= selfBestBlockNumber){
            this.evtMgr.newEvent(new EventConsensus(EventConsensus.CALLBACK.ON_SYNC_DONE));
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "<network-best-block-updated remote-num={} self-num={} known-network-num={} send-on-sync-done>",
                        _nodeBestBlockNumber, selfBestBlockNumber, this.netBestStatus.get().blockNumber);
            }
        } else {
            if (LOG.isDebugEnabled())
                LOG.debug(
                        "<network-best-block-updated remote-num={} self-num={} known-network-num={} continue-on-sync>",
                        _nodeBestBlockNumber, selfBestBlockNumber, this.netBestStatus.get().blockNumber);
        }
    }

    public void init(final IP2pMgr _p2pMgr, final IEventMgr _evtMgr, final int _syncForwardMax,
            final int _blocksQueueMax, final boolean _showStatus) {
        this.p2pMgr = _p2pMgr;
        this.blockchain = AionBlockchainImpl.inst();
        this.evtMgr = _evtMgr;
        this.syncForwardMax = _syncForwardMax;
        this.blocksQueueMax = _blocksQueueMax;
        IChainCfg chainCfg = new ChainConfiguration();
        this.blockHeaderValidator = chainCfg.createBlockHeaderValidator();

        setupEventHandler();

        Thread getBodiesThread = new Thread(this::processGetBlocks, "sync-blocks");
        getBodiesThread.setPriority(Thread.MAX_PRIORITY);
        getBodiesThread.start();

        Thread importBlocksThread = new Thread(this::processImportBlocks, "sync-import");
        importBlocksThread.setPriority(Thread.MAX_PRIORITY);
        importBlocksThread.start();

        scheduledWorkers = new ScheduledThreadPoolExecutor(1);
        scheduledWorkers.allowCoreThreadTimeOut(true);

        if (_showStatus)
            scheduledWorkers.scheduleWithFixedDelay(() -> {
                Thread.currentThread().setName("sync-status");
                AionBlock blk = blockchain.getBestBlock();
                System.out.println("[sync-status self=" + blk.getNumber() + "/"
                        + Hex.toHexString(this.blockchain.getBestBlockHash()) + "/"
                        + this.blockchain.getTotalDifficulty().toString(10)
                        + " network="
                        + this.netBestStatus.get().blockNumber + "/"
                        + Hex.toHexString(netBestStatus.get().blockHash) + "/"
                        + netBestStatus.get().totalDiff.toString(10)
                        + " blocks-queue-size=" + importedBlocks.size() + "]");
            }, 0, 5000, TimeUnit.MILLISECONDS);
        scheduledWorkers.scheduleWithFixedDelay(() -> {
            for (INode node : p2pMgr.getActiveNodes().values()) {
                p2pMgr.send(node.getIdHash(), reqStatus);
            }

        }, 1000, STATUS_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void setupEventHandler() {
        List<IEvent> events = new ArrayList<>();
        events.add(new EventConsensus(EventConsensus.CALLBACK.ON_SYNC_DONE));
        this.evtMgr.registerEvent(events);
    }

    /**
     *
     * @param _nodeIdHashcode
     *            int
     * @param _displayId
     *            String
     * @param _headers
     *            List validate headers batch and add batch to imported headers
     */
    public void validateAndAddHeaders(int _nodeIdHashcode, String _displayId, final List<A0BlockHeader> _headers) {

        if (_headers == null || _headers.isEmpty()) {
            return;
        }

        _headers.sort((h1, h2) -> (int) (h1.getNumber() - h2.getNumber()));
        Iterator<A0BlockHeader> it = _headers.iterator();
        while (it.hasNext()) {

            A0BlockHeader h = it.next();
            boolean valid = this.blockHeaderValidator.validate(h);
            boolean imported = savedHashes.containsKey(new ByteArrayWrapper(h.getHash()));

            // drop all batch
            if (!valid) {
                return;
            }
            if (imported) {
                it.remove();
            }
        }

        importedHeaders.add(new HeadersWrapper(_nodeIdHashcode, _headers));

        if (LOG.isDebugEnabled()) {
            LOG.debug("<incoming-headers size={} from-num={} to-num={} from-node={}>", _headers.size(),
                    _headers.get(0).getNumber(), _headers.get(_headers.size() - 1).getNumber(), _displayId);
        }
    }

    /**
     * @param _nodeIdHashcode
     *            int
     * @param _displayId
     *            String
     * @param _bodies
     *            List<byte[]> Assemble and validate blocks batch and add batch
     *            to import queue from network response blocks bodies
     */
    public void validateAndAddBlocks(int _nodeIdHashcode, String _displayId, final List<byte[]> _bodies) {

        if (importedBlocks.size() > blocksQueueMax)
            return;

        HeadersWrapper hw = this.sentHeaders.remove(_nodeIdHashcode);
        if (hw == null || _bodies == null)
            return;

        // assemble batch
        List<A0BlockHeader> headers = hw.getHeaders();
        List<AionBlock> blocks = new ArrayList<>(_bodies.size());
        Iterator<A0BlockHeader> headerIt = headers.iterator();
        Iterator<byte[]> bodyIt = _bodies.iterator();
        while (headerIt.hasNext() && bodyIt.hasNext()) {
            AionBlock block = AionBlock.createBlockFromNetwork(headerIt.next(), bodyIt.next());
            if (block == null) {
                LOG.error("<assemble-and-validate-blocks from-node={}>", _displayId);
                break;
            } else
                blocks.add(block);
        }

        int m = blocks.size();
        if (m == 0)
            return;

        // sort this batch
        if (m > 1)
            blocks.sort((b1, b2) -> b1.getNumber() > b2.getNumber() ? 1 : 0);
        if (LOG.isDebugEnabled()) {
            LOG.debug("<incoming-bodies size={} from-num={} to-num={} from-node={}>", m, blocks.get(0).getNumber(),
                    blocks.get(blocks.size() - 1).getNumber(), _displayId);
        }

        // add batch
        importedBlocks.addAll(blocks);
    }

    /**
     * fetch headers routine
     */
    private void getHeaders() {

        AionBlock selfBlock = this.blockchain.getBestBlock();
        long selfNum = selfBlock.getNumber();
        long retargetNum = jump.get();

        // retarget future if its higher than self
        long selfBest = Math.max(selfNum, retargetNum);

        Set<Integer> ids = new HashSet<>();

        List<INode> filtered = p2pMgr.getActiveNodes().values().stream().filter(
                (n) -> netBestStatus.get().totalDiff != null &&
                        n.getTotalDifficulty() != null &&
                    (new BigInteger(1, n.getTotalDifficulty())).compareTo(netBestStatus.get().totalDiff) >= 0).collect(Collectors.toList());

        Random r = new Random(System.currentTimeMillis());
        for (int i = 0; i < 3; i++) {
            if (filtered.size() > 0) {
                INode node = filtered.get(r.nextInt(filtered.size()));
                if (!ids.contains(node.getIdHash())) {
                    long from = Math.max(1, selfBest - 128);
                    long to = selfBest + this.syncForwardMax;
                    int take = (int) (to - from) + 1;
                    ids.add(node.getIdHash());
                    this.p2pMgr.send(node.getIdHash(), new ReqBlocksHeaders(from, take));
                    // ids.put(node.getIdHash(), new HeaderQuery(node.getIdShort(), from, take));
                    LOG.debug("Requesting blocks: from = {}, to = {}, node = {}", from , to, node.getIdShort());
                }
            }
        }
//        ids.forEach((k, v) -> {
//            // System.out.println("head req from " + v.from + " take " + v.take);
//            this.p2pMgr.send(k, new ReqBlocksHeaders(v.from, v.take));
//        });
    }

    private void processGetBlocks() {
        while (start.get()) {
            HeadersWrapper hw;
            try {
                hw = importedHeaders.take();
            } catch (InterruptedException e) {
                return;
            }

            int idHash = hw.getNodeIdHash();
            List<A0BlockHeader> headers = hw.getHeaders();
            synchronized (sentHeaders) {
                HeadersWrapper hw1 = sentHeaders.get(idHash);
                // already sent, check timeout and add it back if not timeout
                // yet
                if (hw1 != null) {
                    // expired
                    if ((System.currentTimeMillis() - hw1.getTimeout()) > SENT_HEADERS_TIMEOUT) {
                        sentHeaders.put(idHash, hw);
                        List<byte[]> headerHashes = new ArrayList<>();
                        for (A0BlockHeader h : headers) {
                            headerHashes.add(h.getHash());
                        }
                        this.p2pMgr.send(idHash, new ReqBlocksBodies(headerHashes));
                    }
                } else {
                    this.sentHeaders.put(idHash, hw);
                    List<byte[]> headerHashes = new ArrayList<>();
                    for (A0BlockHeader h : headers) {
                        headerHashes.add(h.getHash());
                    }
                    this.p2pMgr.send(idHash, new ReqBlocksBodies(headerHashes));
                }
            }
        }
    }

    private void processImportBlocks() {

        while (start.get()) {
            try {
                long start = System.currentTimeMillis();
                long blockNumberIndex = 0;

                List<AionBlock> batch = new ArrayList<>();

                while ((System.currentTimeMillis() - start) < 10) {

                    AionBlock b = importedBlocks.peek();

                    // continue on batched blocks
                    if (b == null) {
                        continue;
                    }

                    // break if start of next batch
                    if (blockNumberIndex > 0 && b.getNumber() != (blockNumberIndex + 1)) {
                        start = 0;
                        continue;
                    }

                    b = importedBlocks.take();
                    if (!savedHashes.containsKey(ByteArrayWrapper.wrap(b.getHash()))) {
                        batch.add(b);
                    }
                }

                // sleep if no batch empty then continue
                if (batch.size() == 0) {
                    Thread.sleep(1000);
                    continue;
                }



                boolean fetchAheadTriggerUsed = false;

                for (AionBlock b : batch) {
                    ImportResult importResult = this.blockchain.tryToConnect(b);
                    switch (importResult) {
                    case IMPORTED_BEST:
                        if (LOG.isInfoEnabled()) {
                            LOG.info("<import-best num={} hash={} txs={}>", b.getNumber(), b.getShortHash(),
                                    b.getTransactionsList().size());
                        }

                        // re-targeting for next batch blocks headers
                        if (!fetchAheadTriggerUsed) {
                            jump.set(batch.get(batch.size() - 1).getNumber());
                            fetchAheadTriggerUsed = true;
                            getHeaders();
                        }

                        savedHashes.put(ByteArrayWrapper.wrap(b.getHash()), null);
                        break;
                    case IMPORTED_NOT_BEST:
                        if (LOG.isInfoEnabled()) {
                            LOG.info("<import-not-best num={} hash={} txs={}>", b.getNumber(), b.getShortHash(),
                                    b.getTransactionsList().size());
                        }

                        savedHashes.put(ByteArrayWrapper.wrap(b.getHash()), null);
                        break;
                    case EXIST:
                        // still exist
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("<import-fail err=block-exit num={} hash={} txs={}>", b.getNumber(),
                                    b.getShortHash(), b.getTransactionsList().size());
                        }

                        savedHashes.put(ByteArrayWrapper.wrap(b.getHash()), null);
                        break;
                    case NO_PARENT:
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("<import-fail err=no-parent num={} hash={}>", b.getNumber(), b.getShortHash());
                        }

                        // reset and skip current batch
                        jump.set(0);
                        continue;
                    case INVALID_BLOCK:
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("<import-fail err=invalid-block num={} hash={} txs={}>", b.getNumber(),
                                    b.getShortHash(), b.getTransactionsList().size());
                        }
                        break;
                    default:
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("<import-res-unknown>");
                        }
                        break;
                    }
                }
            } catch (Exception ex) {
                return;
            }
        }
    }

    public void shutdown() {
        scheduledWorkers.shutdown();
        start.set(false);
    }

    public long getNetworkBestBlockNumber() {
        return this.netBestStatus.get().blockNumber;
    }
}
