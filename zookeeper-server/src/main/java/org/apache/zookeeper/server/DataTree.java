/*
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

package org.apache.zookeeper.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.DigestWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.Watcher.WatcherType;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.audit.AuditConstants;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.common.PathTrie;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.server.watch.IWatchManager;
import org.apache.zookeeper.server.watch.WatchManagerFactory;
import org.apache.zookeeper.server.watch.WatcherMode;
import org.apache.zookeeper.server.watch.WatcherOrBitSet;
import org.apache.zookeeper.server.watch.WatchesPathReport;
import org.apache.zookeeper.server.watch.WatchesReport;
import org.apache.zookeeper.server.watch.WatchesSummary;
import org.apache.zookeeper.txn.CheckVersionTxn;
import org.apache.zookeeper.txn.CloseSessionTxn;
import org.apache.zookeeper.txn.CreateContainerTxn;
import org.apache.zookeeper.txn.CreateTTLTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class maintains the tree data structure. It doesn't have any networking
 * or client connection code in it so that it can be tested in a standalone way.
 *
 * <p>The tree maintains two parallel data structures: a hashtable that maps from
 * full paths to DataNodes and a tree of DataNodes. All accesses to a path is
 * through the hashtable. The tree is traversed only when serializing to disk.
 */
public class DataTree {

    private static final Logger LOG = LoggerFactory.getLogger(DataTree.class);

    private final RateLogger RATE_LOGGER = new RateLogger(LOG, 15 * 60 * 1000);

    /**
     * This map provides a fast lookup to the data nodes. The tree is the
     * source of truth and is where all the locking occurs
     */
    private final NodeHashMap nodes;

    private IWatchManager dataWatches;

    private IWatchManager childWatches;

    /** cached total size of paths and data for all DataNodes */
    private final AtomicLong nodeDataSize = new AtomicLong(0);

    /** the root of zookeeper tree */
    private static final String rootZookeeper = "/";

    /** the zookeeper nodes that acts as the management and status node **/
    private static final String procZookeeper = Quotas.procZookeeper;

    /** this will be the string that's stored as a child of root */
    private static final String procChildZookeeper = procZookeeper.substring(1);

    /**
     * the zookeeper quota node that acts as the quota management node for
     * zookeeper
     */
    private static final String quotaZookeeper = Quotas.quotaZookeeper;

    /** this will be the string that's stored as a child of /zookeeper */
    private static final String quotaChildZookeeper = quotaZookeeper.substring(procZookeeper.length() + 1);

    /**
     * the zookeeper config node that acts as the config management node for
     * zookeeper
     */
    private static final String configZookeeper = ZooDefs.CONFIG_NODE;

    /** this will be the string that's stored as a child of /zookeeper */
    private static final String configChildZookeeper = configZookeeper.substring(procZookeeper.length() + 1);

    /**
     * the path trie that keeps track of the quota nodes in this datatree
     */
    private final PathTrie pTrie = new PathTrie();

    /**
     * over-the-wire size of znode stat. Counting the fields of Stat class
     */
    public static final int STAT_OVERHEAD_BYTES = (6 * 8) + (5 * 4);

    /**
     * This hashtable lists the paths of the ephemeral nodes of a session.
     */
    private final Map<Long, HashSet<String>> ephemerals = new ConcurrentHashMap<>();

    /**
     * This set contains the paths of all container nodes
     */
    private final Set<String> containers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * This set contains the paths of all ttl nodes
     */
    private final Set<String> ttls = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final ReferenceCountedACLCache aclCache = new ReferenceCountedACLCache();

    // The maximum number of tree digests that we will keep in our history
    public static final int DIGEST_LOG_LIMIT = 1024;

    // Dump digest every 128 txns, in hex it's 80, which will make it easier
    // to align and compare between servers.
    public static final int DIGEST_LOG_INTERVAL = 128;

    // If this is not null, we are actively looking for a target zxid that we
    // want to validate the digest for
    private ZxidDigest digestFromLoadedSnapshot;

    // The digest associated with the highest zxid in the data tree.
    private volatile ZxidDigest lastProcessedZxidDigest;

    private boolean firstMismatchTxn = true;

    // Will be notified when digest mismatch event triggered.
    private final List<DigestWatcher> digestWatchers = new ArrayList<>();

    // The historical digests list.
    private final LinkedList<ZxidDigest> digestLog = new LinkedList<>();

    private final DigestCalculator digestCalculator;

    @SuppressWarnings("unchecked")
    public Set<String> getEphemerals(long sessionId) {
        HashSet<String> ret = ephemerals.get(sessionId);
        if (ret == null) {
            return new HashSet<>();
        }
        synchronized (ret) {
            return (HashSet<String>) ret.clone();
        }
    }

    public Set<String> getContainers() {
        return new HashSet<>(containers);
    }

    public Set<String> getTtls() {
        return new HashSet<>(ttls);
    }

    public Collection<Long> getSessions() {
        return ephemerals.keySet();
    }

    public DataNode getNode(String path) {
        return nodes.get(path);
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getWatchCount() {
        return dataWatches.size() + childWatches.size();
    }

    public int getEphemeralsCount() {
        int result = 0;
        for (HashSet<String> set : ephemerals.values()) {
            result += set.size();
        }
        return result;
    }

    /**
     * Get the size of the nodes based on path and data length.
     *
     * @return size of the data
     */
    public long approximateDataSize() {
        long result = 0;
        for (Map.Entry<String, DataNode> entry : nodes.entrySet()) {
            DataNode value = entry.getValue();
            synchronized (value) {
                result += getNodeSize(entry.getKey(), value.data);
            }
        }
        return result;
    }

    /**
     * Get the size of the node based on path and data length.
     */
    private static long getNodeSize(String path, byte[] data) {
        return (path == null ? 0 : path.length()) + (data == null ? 0 : data.length);
    }

    public long cachedApproximateDataSize() {
        return nodeDataSize.get();
    }

    /**
     * This is a pointer to the root of the DataTree. It is the source of truth,
     * but we usually use the nodes hashmap to find nodes in the tree.
     */
    private DataNode root = new DataNode(new byte[0], -1L, new StatPersisted());

    /**
     * create a /zookeeper filesystem that is the proc filesystem of zookeeper
     */
    private final DataNode procDataNode = new DataNode(new byte[0], -1L, new StatPersisted());

    /**
     * create a /zookeeper/quota node for maintaining quota properties for
     * zookeeper
     */
    private final DataNode quotaDataNode = new DataNode(new byte[0], -1L, new StatPersisted());

    public DataTree() {
        this(new DigestCalculator());
    }

    DataTree(DigestCalculator digestCalculator) {
        this.digestCalculator = digestCalculator;
        nodes = new NodeHashMapImpl(digestCalculator);

        // rather than fight it, let root have an alias
        nodes.put("", root);
        nodes.putWithoutDigest(rootZookeeper, root);

        // add the proc node and quota node
        root.addChild(procChildZookeeper);
        nodes.put(procZookeeper, procDataNode);

        procDataNode.addChild(quotaChildZookeeper);
        nodes.put(quotaZookeeper, quotaDataNode);

        addConfigNode();

        nodeDataSize.set(approximateDataSize());
        try {
            dataWatches = WatchManagerFactory.createWatchManager();
            childWatches = WatchManagerFactory.createWatchManager();
        } catch (Exception e) {
            LOG.error("Unexpected exception when creating WatchManager, exiting abnormally", e);
            ServiceUtils.requestSystemExit(ExitCode.UNEXPECTED_ERROR.getValue());
        }
    }

    /**
     * create a /zookeeper/config node for maintaining the configuration (membership and quorum system) info for
     * zookeeper
     */
    public void addConfigNode() {
        DataNode zookeeperZnode = nodes.get(procZookeeper);
        if (zookeeperZnode != null) { // should always be the case
            zookeeperZnode.addChild(configChildZookeeper);
        } else {
            assert false : "There's no /zookeeper znode - this should never happen.";
        }

        nodes.put(configZookeeper, new DataNode(new byte[0], -1L, new StatPersisted()));
        try {
            // Reconfig node is access controlled by default (ZOOKEEPER-2014).
            setACL(configZookeeper, ZooDefs.Ids.READ_ACL_UNSAFE, -1);
        } catch (NoNodeException e) {
            assert false : "There's no " + configZookeeper + " znode - this should never happen.";
        }
    }

    /**
     * is the path one of the special paths owned by zookeeper.
     *
     * @param path
     *            the path to be checked
     * @return true if a special path. false if not.
     */
    boolean isSpecialPath(String path) {
        return rootZookeeper.equals(path)
               || procZookeeper.equals(path)
               || quotaZookeeper.equals(path)
               || configZookeeper.equals(path);
    }

    public static void copyStatPersisted(StatPersisted from, StatPersisted to) {
        to.setAversion(from.getAversion());
        to.setCtime(from.getCtime());
        to.setCversion(from.getCversion());
        to.setCzxid(from.getCzxid());
        to.setMtime(from.getMtime());
        to.setMzxid(from.getMzxid());
        to.setPzxid(from.getPzxid());
        to.setVersion(from.getVersion());
        to.setEphemeralOwner(from.getEphemeralOwner());
    }

    public static void copyStat(Stat from, Stat to) {
        to.setAversion(from.getAversion());
        to.setCtime(from.getCtime());
        to.setCversion(from.getCversion());
        to.setCzxid(from.getCzxid());
        to.setMtime(from.getMtime());
        to.setMzxid(from.getMzxid());
        to.setPzxid(from.getPzxid());
        to.setVersion(from.getVersion());
        to.setEphemeralOwner(from.getEphemeralOwner());
        to.setDataLength(from.getDataLength());
        to.setNumChildren(from.getNumChildren());
    }

    /**
     * update the count/bytes of this stat data node
     *
     * @param lastPrefix
     *            the path of the node that has a quota.
     * @param bytesDiff
     *            the diff to be added to number of bytes
     * @param countDiff
     *            the diff to be added to the count
     */
    public void updateQuotaStat(String lastPrefix, long bytesDiff, int countDiff) {
        String statNodePath = Quotas.statPath(lastPrefix);
        DataNode statNode = nodes.get(statNodePath);

        if (statNode == null) {
            // should not happen
            LOG.error("Missing node for stat {}", statNodePath);
            return;
        }

        synchronized (statNode) {
            StatsTrack updatedStat = new StatsTrack(statNode.data);
            updatedStat.setCount(updatedStat.getCount() + countDiff);
            updatedStat.setBytes(updatedStat.getBytes() + bytesDiff);
            statNode.data = updatedStat.getStatsBytes();
        }
    }

    /**
     * Add a new node to the DataTree.
     * @param path
     *            Path for the new node.
     * @param data
     *            Data to store in the node.
     * @param acl
     *            Node acls
     * @param ephemeralOwner
     *            the session id that owns this node. -1 indicates this is not
     *            an ephemeral node.
     * @param zxid
     *            Transaction ID
     * @param time
     * @throws NodeExistsException
     * @throws NoNodeException
     */
    public void createNode(final String path, byte[] data, List<ACL> acl, long ephemeralOwner, int parentCVersion, long zxid, long time) throws NoNodeException, NodeExistsException {
        createNode(path, data, acl, ephemeralOwner, parentCVersion, zxid, time, null);
    }

    /**
     * Add a new node to the DataTree.
     * @param path
     *            Path for the new node.
     * @param data
     *            Data to store in the node.
     * @param acl
     *            Node acls
     * @param ephemeralOwner
     *            the session id that owns this node. -1 indicates this is not
     *            an ephemeral node.
     * @param zxid
     *            Transaction ID
     * @param time
     * @param outputStat
     *            A Stat object to store Stat output results into.
     * @throws NodeExistsException
     * @throws NoNodeException
     */
    public void createNode(final String path, byte[] data, List<ACL> acl, long ephemeralOwner, int parentCVersion, long zxid, long time, Stat outputStat) throws NoNodeException, NodeExistsException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        StatPersisted stat = createStat(zxid, time, ephemeralOwner);
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new NoNodeException();
        }
        List<ACL> parentAcl;
        synchronized (parent) {
            parentAcl = getACL(parent);

            // Add the ACL to ACL cache first, to avoid the ACL not being
            // created race condition during fuzzy snapshot sync.
            //
            // This is the simplest fix, which may add ACL reference count
            // again if it's already counted in the ACL map of fuzzy
            // snapshot, which might also happen for deleteNode txn, but
            // at least it won't cause the ACL not exist issue.
            //
            // Later we can audit and delete all non-referenced ACLs from
            // ACL map when loading the snapshot/txns from disk, like what
            // we did for the global sessions.
            Long acls = aclCache.convertAcls(acl);

            DataNode existingChild = nodes.get(path);
            if (existingChild != null) {
                existingChild.acl = acls;
                throw new NodeExistsException();
            }

            nodes.preChange(parentName, parent);
            if (parentCVersion == -1) {
                parentCVersion = parent.stat.getCversion();
                parentCVersion++;
            }
            // There is possibility that we'll replay txns for a node which
            // was created and then deleted in the fuzzy range, and it's not
            // exist in the snapshot, so replay the creation might revert the
            // cversion and pzxid, need to check and only update when it's
            // larger.
            if (parentCVersion > parent.stat.getCversion()) {
                parent.stat.setCversion(parentCVersion);
                parent.stat.setPzxid(zxid);
            }
            DataNode child = new DataNode(data, acls, stat);
            parent.addChild(childName);
            nodes.postChange(parentName, parent);
            nodeDataSize.addAndGet(getNodeSize(path, child.data));
            nodes.put(path, child);
            EphemeralType ephemeralType = EphemeralType.get(ephemeralOwner);
            if (ephemeralType == EphemeralType.CONTAINER) {
                containers.add(path);
            } else if (ephemeralType == EphemeralType.TTL) {
                ttls.add(path);
            } else if (ephemeralOwner != 0) {
                HashSet<String> list = ephemerals.computeIfAbsent(ephemeralOwner, k -> new HashSet<>());
                synchronized (list) {
                    list.add(path);
                }
            }
            if (outputStat != null) {
                child.copyStat(outputStat);
            }
        }
        // now check if its one of the zookeeper node child
        if (parentName.startsWith(quotaZookeeper)) {
            // now check if it's the limit node
            if (Quotas.limitNode.equals(childName)) {
                // this is the limit node
                // get the parent and add it to the trie
                pTrie.addPath(Quotas.trimQuotaPath(parentName));
            }
            if (Quotas.statNode.equals(childName)) {
                updateQuotaForPath(Quotas.trimQuotaPath(parentName));
            }
        }

        String lastPrefix = getMaxPrefixWithQuota(path);
        long bytes = data == null ? 0 : data.length;
        // also check to update the quotas for this node
        if (lastPrefix != null) {    // ok we have some match and need to update
            updateQuotaStat(lastPrefix, bytes, 1);
        }
        updateWriteStat(path, bytes);
        dataWatches.triggerWatch(path, Event.EventType.NodeCreated, zxid, acl);
        childWatches.triggerWatch(parentName.equals("") ? "/" : parentName,
            Event.EventType.NodeChildrenChanged, zxid, parentAcl);
    }

    /**
     * remove the path from the datatree
     *
     * @param path
     *            the path to of the node to be deleted
     * @param zxid
     *            the current zxid
     * @throws NoNodeException
     */
    public void deleteNode(String path, long zxid) throws NoNodeException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);

        // The child might already be deleted during taking fuzzy snapshot,
        // but we still need to update the pzxid here before throw exception
        // for no such child
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new NoNodeException();
        }
        synchronized (parent) {
            nodes.preChange(parentName, parent);
            parent.removeChild(childName);
            // Only update pzxid when the zxid is larger than the current pzxid,
            // otherwise we might override some higher pzxid set by a CreateTxn,
            // which could cause the cversion and pzxid inconsistent
            if (zxid > parent.stat.getPzxid()) {
                parent.stat.setPzxid(zxid);
            }
            nodes.postChange(parentName, parent);
        }

        DataNode node = nodes.get(path);
        if (node == null) {
            throw new NoNodeException();
        }
        List<ACL> acl;
        nodes.remove(path);
        synchronized (node) {
            acl = getACL(node);
            aclCache.removeUsage(node.acl);
            nodeDataSize.addAndGet(-getNodeSize(path, node.data));
        }

        // Synchronized to sync the containers and ttls change, probably
        // only need to sync on containers and ttls, will update it in a
        // separate patch.
        List<ACL> parentAcl;
        synchronized (parent) {
            parentAcl = getACL(parent);
            long owner = node.stat.getEphemeralOwner();
            EphemeralType ephemeralType = EphemeralType.get(owner);
            if (ephemeralType == EphemeralType.CONTAINER) {
                containers.remove(path);
            } else if (ephemeralType == EphemeralType.TTL) {
                ttls.remove(path);
                ServerMetrics.getMetrics().TTL_NODE_DELETED_COUNT.add(1);
            } else if (owner != 0) {
                Set<String> nodes = ephemerals.get(owner);
                if (nodes != null) {
                    synchronized (nodes) {
                        nodes.remove(path);
                    }
                }
            }
        }

        if (parentName.startsWith(procZookeeper) && Quotas.limitNode.equals(childName)) {
            // delete the node in the trie.
            // we need to update the trie as well
            pTrie.deletePath(Quotas.trimQuotaPath(parentName));
        }

        // also check to update the quotas for this node
        String lastPrefix = getMaxPrefixWithQuota(path);
        if (lastPrefix != null) {
            // ok we have some match and need to update
            long bytes;
            synchronized (node) {
                bytes = (node.data == null ? 0 : -(node.data.length));
            }
            updateQuotaStat(lastPrefix, bytes, -1);
        }

        updateWriteStat(path, 0L);

        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(
                LOG,
                ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                "dataWatches.triggerWatch " + path);
            ZooTrace.logTraceMessage(
                LOG,
                ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                "childWatches.triggerWatch " + parentName);
        }

        WatcherOrBitSet processed = dataWatches.triggerWatch(path, EventType.NodeDeleted, zxid, acl);
        childWatches.triggerWatch(path, EventType.NodeDeleted, zxid, acl, processed);
        childWatches.triggerWatch("".equals(parentName) ? "/" : parentName,
            EventType.NodeChildrenChanged, zxid, parentAcl);
    }

    public Stat setData(String path, byte[] data, int version, long zxid, long time) throws NoNodeException {
        Stat s = new Stat();
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new NoNodeException();
        }
        List<ACL> acl;
        byte[] lastData;
        synchronized (n) {
            acl = getACL(n);
            lastData = n.data;
            nodes.preChange(path, n);
            n.data = data;
            n.stat.setMtime(time);
            n.stat.setMzxid(zxid);
            n.stat.setVersion(version);
            n.copyStat(s);
            nodes.postChange(path, n);
        }

        // first do a quota check if the path is in a quota subtree.
        String lastPrefix = getMaxPrefixWithQuota(path);
        long bytesDiff = (data == null ? 0 : data.length) - (lastData == null ? 0 : lastData.length);
        // now update if the path is in a quota subtree.
        long dataBytes = data == null ? 0 : data.length;
        if (lastPrefix != null) {
            updateQuotaStat(lastPrefix, bytesDiff, 0);
        }
        nodeDataSize.addAndGet(getNodeSize(path, data) - getNodeSize(path, lastData));

        updateWriteStat(path, dataBytes);
        dataWatches.triggerWatch(path, EventType.NodeDataChanged, zxid, acl);
        return s;
    }

    /**
     * If there is a quota set, return the appropriate prefix for that quota
     * Else return null
     * @param path The ZK path to check for quota
     * @return Max quota prefix, or null if none
     */
    public String getMaxPrefixWithQuota(String path) {
        // do nothing for the root.
        // we are not keeping a quota on the zookeeper
        // root node for now.
        String lastPrefix = pTrie.findMaxPrefix(path);

        if (rootZookeeper.equals(lastPrefix) || lastPrefix.isEmpty()) {
            return null;
        } else {
            return lastPrefix;
        }
    }

    public void addWatch(String basePath, Watcher watcher, int mode) {
        WatcherMode watcherMode = WatcherMode.fromZooDef(mode);
        dataWatches.addWatch(basePath, watcher, watcherMode);
        if (watcherMode != WatcherMode.PERSISTENT_RECURSIVE) {
            childWatches.addWatch(basePath, watcher, watcherMode);
        }
    }

    public byte[] getData(String path, Stat stat, Watcher watcher) throws NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new NoNodeException();
        }
        byte[] data;
        synchronized (n) {
            n.copyStat(stat);
            if (watcher != null) {
                dataWatches.addWatch(path, watcher);
            }
            data = n.data;
        }
        updateReadStat(path, data == null ? 0 : data.length);
        return data;
    }

    public Stat statNode(String path, Watcher watcher) throws NoNodeException {
        if (watcher != null) {
            dataWatches.addWatch(path, watcher);
        }
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new NoNodeException();
        }
        Stat stat = new Stat();
        synchronized (n) {
            n.copyStat(stat);
        }
        updateReadStat(path, 0L);
        return stat;
    }

    public List<String> getChildren(String path, Stat stat, Watcher watcher) throws NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new NoNodeException();
        }
        List<String> children;
        synchronized (n) {
            if (stat != null) {
                n.copyStat(stat);
            }
            children = new ArrayList<>(n.getChildren());

            if (watcher != null) {
                childWatches.addWatch(path, watcher);
            }
        }

        int bytes = 0;
        for (String child : children) {
            bytes += child.length();
        }
        updateReadStat(path, bytes);

        return children;
    }

    public int getAllChildrenNumber(String path) {
        // cull out these two keys:"", "/"
        if ("/".equals(path)) {
            return nodes.size() - 2;
        }

        return (int) nodes.entrySet().parallelStream().filter(entry -> entry.getKey().startsWith(path + "/")).count();
    }

    public Stat setACL(String path, List<ACL> acl, int version) throws NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new NoNodeException();
        }
        synchronized (n) {
            Stat stat = new Stat();
            aclCache.removeUsage(n.acl);
            nodes.preChange(path, n);
            n.stat.setAversion(version);
            n.acl = aclCache.convertAcls(acl);
            n.copyStat(stat);
            nodes.postChange(path, n);
            return stat;
        }
    }

    public List<ACL> getACL(String path, Stat stat) throws NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new NoNodeException();
        }
        synchronized (n) {
            if (stat != null) {
                n.copyStat(stat);
            }
            return new ArrayList<>(aclCache.convertLong(n.acl));
        }
    }

    public List<ACL> getACL(DataNode node) {
        synchronized (node) {
            return aclCache.convertLong(node.acl);
        }
    }

    public int aclCacheSize() {
        return aclCache.size();
    }

    public static class ProcessTxnResult {

        public long clientId;

        public int cxid;

        public long zxid;

        public int err;

        public int type;

        public String path;

        public Stat stat;

        public List<ProcessTxnResult> multiResult;

        /**
         * Equality is defined as the clientId and the cxid being the same. This
         * allows us to use hash tables to track completion of transactions.
         *
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object o) {
            if (o instanceof ProcessTxnResult) {
                ProcessTxnResult other = (ProcessTxnResult) o;
                return other.clientId == clientId && other.cxid == cxid;
            }
            return false;
        }

        /**
         * See equals() to find the rationale for how this hashcode is generated.
         *
         * @see ProcessTxnResult#equals(Object)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            return (int) ((clientId ^ cxid) % Integer.MAX_VALUE);
        }

    }

    public volatile long lastProcessedZxid = 0;

    public ProcessTxnResult processTxn(TxnHeader header, Record txn, TxnDigest digest) {
        ProcessTxnResult result = processTxn(header, txn);
        compareDigest(header, txn, digest);
        return result;
    }

    public ProcessTxnResult processTxn(TxnHeader header, Record txn) {
        return this.processTxn(header, txn, false);
    }

    public ProcessTxnResult processTxn(TxnHeader header, Record txn, boolean isSubTxn) {
        ProcessTxnResult rc = new ProcessTxnResult();

        try {
            rc.clientId = header.getClientId();
            rc.cxid = header.getCxid();
            rc.zxid = header.getZxid();
            rc.type = header.getType();
            rc.err = 0;
            rc.multiResult = null;
            switch (header.getType()) {
            case OpCode.create:
                CreateTxn createTxn = (CreateTxn) txn;
                rc.path = createTxn.getPath();
                createNode(
                    createTxn.getPath(),
                    createTxn.getData(),
                    createTxn.getAcl(),
                    createTxn.getEphemeral() ? header.getClientId() : 0,
                    createTxn.getParentCVersion(),
                    header.getZxid(),
                    header.getTime(),
                    null);
                break;
            case OpCode.create2:
                CreateTxn create2Txn = (CreateTxn) txn;
                rc.path = create2Txn.getPath();
                Stat stat = new Stat();
                createNode(
                    create2Txn.getPath(),
                    create2Txn.getData(),
                    create2Txn.getAcl(),
                    create2Txn.getEphemeral() ? header.getClientId() : 0,
                    create2Txn.getParentCVersion(),
                    header.getZxid(),
                    header.getTime(),
                    stat);
                rc.stat = stat;
                break;
            case OpCode.createTTL:
                CreateTTLTxn createTtlTxn = (CreateTTLTxn) txn;
                rc.path = createTtlTxn.getPath();
                stat = new Stat();
                createNode(
                    createTtlTxn.getPath(),
                    createTtlTxn.getData(),
                    createTtlTxn.getAcl(),
                    EphemeralType.TTL.toEphemeralOwner(createTtlTxn.getTtl()),
                    createTtlTxn.getParentCVersion(),
                    header.getZxid(),
                    header.getTime(),
                    stat);
                rc.stat = stat;
                break;
            case OpCode.createContainer:
                CreateContainerTxn createContainerTxn = (CreateContainerTxn) txn;
                rc.path = createContainerTxn.getPath();
                stat = new Stat();
                createNode(
                    createContainerTxn.getPath(),
                    createContainerTxn.getData(),
                    createContainerTxn.getAcl(),
                    EphemeralType.CONTAINER_EPHEMERAL_OWNER,
                    createContainerTxn.getParentCVersion(),
                    header.getZxid(),
                    header.getTime(),
                    stat);
                rc.stat = stat;
                break;
            case OpCode.delete:
            case OpCode.deleteContainer:
                DeleteTxn deleteTxn = (DeleteTxn) txn;
                rc.path = deleteTxn.getPath();
                deleteNode(deleteTxn.getPath(), header.getZxid());
                break;
            case OpCode.reconfig:
            case OpCode.setData:
                SetDataTxn setDataTxn = (SetDataTxn) txn;
                rc.path = setDataTxn.getPath();
                rc.stat = setData(
                    setDataTxn.getPath(),
                    setDataTxn.getData(),
                    setDataTxn.getVersion(),
                    header.getZxid(),
                    header.getTime());
                break;
            case OpCode.setACL:
                SetACLTxn setACLTxn = (SetACLTxn) txn;
                rc.path = setACLTxn.getPath();
                rc.stat = setACL(setACLTxn.getPath(), setACLTxn.getAcl(), setACLTxn.getVersion());
                break;
            case OpCode.closeSession:
                long sessionId = header.getClientId();
                if (txn != null) {
                    killSession(sessionId, header.getZxid(),
                            ephemerals.remove(sessionId),
                            ((CloseSessionTxn) txn).getPaths2Delete());
                } else {
                    killSession(sessionId, header.getZxid());
                }
                break;
            case OpCode.error:
                ErrorTxn errTxn = (ErrorTxn) txn;
                rc.err = errTxn.getErr();
                break;
            case OpCode.check:
                CheckVersionTxn checkTxn = (CheckVersionTxn) txn;
                rc.path = checkTxn.getPath();
                break;
            case OpCode.multi:
                MultiTxn multiTxn = (MultiTxn) txn;
                List<Txn> txns = multiTxn.getTxns();
                rc.multiResult = new ArrayList<>();
                boolean failed = false;
                for (Txn subtxn : txns) {
                    if (subtxn.getType() == OpCode.error) {
                        failed = true;
                        break;
                    }
                }

                boolean post_failed = false;
                for (Txn subtxn : txns) {
                    final Supplier<Record> supplier;
                    switch (subtxn.getType()) {
                    case OpCode.create:
                    case OpCode.create2:
                        supplier = CreateTxn::new;
                        break;
                    case OpCode.createTTL:
                        supplier = CreateTTLTxn::new;
                        break;
                    case OpCode.createContainer:
                        supplier = CreateContainerTxn::new;
                        break;
                    case OpCode.delete:
                    case OpCode.deleteContainer:
                        supplier = DeleteTxn::new;
                        break;
                    case OpCode.setData:
                        supplier = SetDataTxn::new;
                        break;
                    case OpCode.error:
                        supplier = ErrorTxn::new;
                        post_failed = true;
                        break;
                    case OpCode.check:
                        supplier = CheckVersionTxn::new;
                        break;
                    default:
                        throw new IOException("Invalid type of op: " + subtxn.getType());
                    }

                    final Record record;
                    if (failed && subtxn.getType() != OpCode.error) {
                        int ec = post_failed ? Code.RUNTIMEINCONSISTENCY.intValue() : Code.OK.intValue();
                        subtxn.setType(OpCode.error);
                        record = new ErrorTxn(ec);
                    } else {
                        record = RequestRecord.fromBytes(subtxn.getData()).readRecord(supplier);
                    }

                    assert !failed || (subtxn.getType() == OpCode.error);

                    TxnHeader subHdr = new TxnHeader(
                        header.getClientId(),
                        header.getCxid(),
                        header.getZxid(),
                        header.getTime(),
                        subtxn.getType());
                    ProcessTxnResult subRc = processTxn(subHdr, record, true);
                    rc.multiResult.add(subRc);
                    if (subRc.err != 0 && rc.err == 0) {
                        rc.err = subRc.err;
                    }
                }
                break;
            }
        } catch (KeeperException e) {
            LOG.debug("Failed: {}:{}", header, txn, e);
            rc.err = e.code().intValue();
        } catch (IOException e) {
            LOG.debug("Failed: {}:{}", header, txn, e);
        }

        /*
         * Snapshots are taken lazily. When serializing a node, it's data
         * and children copied in a synchronization block on that node,
         * which means newly created node won't be in the snapshot, so
         * we won't have mismatched cversion and pzxid when replaying the
         * createNode txn.
         *
         * But there is a tricky scenario that if the child is deleted due
         * to session close and re-created in a different global session
         * after that the parent is serialized, then when replay the txn
         * because the node belongs to a different session, replay the
         * closeSession txn won't delete it anymore, and we'll get NODEEXISTS
         * error when replay the createNode txn. In this case, we need to
         * update the cversion and pzxid to the new value.
         *
         * Note, such failures on DT should be seen only during
         * restore.
         */
        if (header.getType() == OpCode.create && rc.err == Code.NODEEXISTS.intValue()) {
            LOG.debug("Adjusting parent cversion for Txn: {} path: {} err: {}", header.getType(), rc.path, rc.err);
            int lastSlash = rc.path.lastIndexOf('/');
            String parentName = rc.path.substring(0, lastSlash);
            CreateTxn cTxn = (CreateTxn) txn;
            try {
                setCversionPzxid(parentName, cTxn.getParentCVersion(), header.getZxid());
            } catch (NoNodeException e) {
                LOG.error("Failed to set parent cversion for: {}", parentName, e);
                rc.err = e.code().intValue();
            }
        } else if (rc.err != Code.OK.intValue()) {
            LOG.debug("Ignoring processTxn failure hdr: {} : error: {}", header.getType(), rc.err);
        }

        /*
         * Things we can only update after the whole txn is applied to data
         * tree.
         *
         * If we update the lastProcessedZxid with the first sub txn in multi
         * and there is a snapshot in progress, it's possible that the zxid
         * associated with the snapshot only include partial of the multi op.
         *
         * When loading snapshot, it will only load the txns after the zxid
         * associated with snapshot file, which could cause data inconsistency
         * due to missing sub txns.
         *
         * To avoid this, we only update the lastProcessedZxid when the whole
         * multi-op txn is applied to DataTree.
         */
        if (!isSubTxn) {
            /*
             * A snapshot might be in progress while we are modifying the data
             * tree. If we set lastProcessedZxid prior to making corresponding
             * change to the tree, then the zxid associated with the snapshot
             * file will be ahead of its contents. Thus, while restoring from
             * the snapshot, the restore method will not apply the transaction
             * for zxid associated with the snapshot file, since the restore
             * method assumes that transaction to be present in the snapshot.
             *
             * To avoid this, we first apply the transaction and then modify
             * lastProcessedZxid.  During restore, we correctly handle the
             * case where the snapshot contains data ahead of the zxid associated
             * with the file.
             */
            if (rc.zxid > lastProcessedZxid) {
                lastProcessedZxid = rc.zxid;
            }

            if (digestFromLoadedSnapshot != null) {
                compareSnapshotDigests(rc.zxid);
            } else {
                // only start recording digest when we're not in fuzzy state
                logZxidDigest(rc.zxid, getTreeDigest());
            }
        }

        return rc;
    }

    void killSession(long session, long zxid) {
        // The list is already removed from the ephemerals,
        // so we do not have to worry about synchronizing on
        // the list. This is only called from FinalRequestProcessor
        // so there is no need for synchronization. The list is not
        // changed here. Only create and delete change the list which
        // are again called from FinalRequestProcessor in sequence.
        killSession(session, zxid, ephemerals.remove(session), null);
    }

    void killSession(long session, long zxid, Set<String> paths2DeleteLocal,
            List<String> paths2DeleteInTxn) {
        if (paths2DeleteInTxn != null) {
            deleteNodes(session, zxid, paths2DeleteInTxn);
        }

        if (paths2DeleteLocal == null) {
            return;
        }

        if (paths2DeleteInTxn != null) {
            // explicitly check and remove to avoid potential performance
            // issue when using removeAll
            for (String path: paths2DeleteInTxn) {
                paths2DeleteLocal.remove(path);
            }
            if (!paths2DeleteLocal.isEmpty()) {
                LOG.warn(
                    "Unexpected extra paths under session {} which are not in txn 0x{}",
                    paths2DeleteLocal,
                    Long.toHexString(zxid));
            }
        }

        deleteNodes(session, zxid, paths2DeleteLocal);
    }

    void deleteNodes(long session, long zxid, Iterable<String> paths2Delete) {
        for (String path : paths2Delete) {
            boolean deleted = false;
            String sessionHex = "0x" + Long.toHexString(session);
            try {
                deleteNode(path, zxid);
                deleted = true;
                LOG.debug("Deleting ephemeral node {} for session {}", path, sessionHex);
            } catch (NoNodeException e) {
                LOG.warn(
                    "Ignoring NoNodeException for path {} while removing ephemeral for dead session {}",
                        path, sessionHex);
            }
            if (ZKAuditProvider.isAuditEnabled()) {
                if (deleted) {
                    ZKAuditProvider.log(ZKAuditProvider.getZKUser(),
                            AuditConstants.OP_DEL_EZNODE_EXP, path, null, null,
                            sessionHex, null, Result.SUCCESS);
                } else {
                    ZKAuditProvider.log(ZKAuditProvider.getZKUser(),
                            AuditConstants.OP_DEL_EZNODE_EXP, path, null, null,
                            sessionHex, null, Result.FAILURE);
                }
            }
        }
    }

    /**
     * An encapsulation class for return value
     */
    private static class Counts {
        long bytes;
        int count;
    }

    /**
     * this method gets the count of nodes and the bytes under a subtree
     *
     * @param path the path to be used
     * @param counts the int count
     */
    private void getCounts(String path, Counts counts) {
        DataNode node = getNode(path);
        if (node == null) {
            return;
        }
        String[] children;
        int len;
        synchronized (node) {
            children = node.getChildren().toArray(new String[0]);
            len = (node.data == null ? 0 : node.data.length);
        }
        // add itself
        counts.count += 1;
        counts.bytes += len;
        for (String child : children) {
            getCounts(path + "/" + child, counts);
        }
    }

    /**
     * update the quota for the given path
     *
     * @param path the path to be used
     */
    private void updateQuotaForPath(String path) {
        Counts c = new Counts();
        getCounts(path, c);
        StatsTrack statsTrack = new StatsTrack();
        statsTrack.setBytes(c.bytes);
        statsTrack.setCount(c.count);
        String statPath = Quotas.statPath(path);
        DataNode node = getNode(statPath);
        // it should exist
        if (node == null) {
            LOG.warn("Missing quota stat node {}", statPath);
            return;
        }
        synchronized (node) {
            nodes.preChange(statPath, node);
            node.data = statsTrack.getStatsBytes();
            nodes.postChange(statPath, node);
        }
    }

    /**
     * this method traverses the quota path and update the path trie and sets
     *
     * @param path the path to be used
     */
    private void traverseNode(String path) {
        DataNode node = getNode(path);
        String[] children;
        synchronized (node) {
            children = node.getChildren().toArray(new String[0]);
        }
        if (children.length == 0) {
            // this node does not have a child
            // is the leaf node
            // check if it's the leaf node
            String endString = "/" + Quotas.limitNode;
            if (path.endsWith(endString)) {
                // ok this is the limit node
                // get the real node and update
                // the count and the bytes
                String realPath = path.substring(Quotas.quotaZookeeper.length(), path.indexOf(endString));
                updateQuotaForPath(realPath);
                this.pTrie.addPath(realPath);
            }
            return;
        }
        for (String child : children) {
            traverseNode(path + "/" + child);
        }
    }

    /**
     * this method sets up the path trie and sets up stats for quota nodes
     */
    private void setupQuota() {
        String quotaPath = Quotas.quotaZookeeper;
        DataNode node = getNode(quotaPath);
        if (node == null) {
            return;
        }
        traverseNode(quotaPath);
    }

    /**
     * this method uses a stringbuilder to create a new path for children. This
     * is faster than string appends ( str1 + str2).
     *
     * @param oa OutputArchive to write to.
     * @param path a string builder.
     * @throws IOException
     */
    void serializeNode(OutputArchive oa, StringBuilder path) throws IOException {
        String pathString = path.toString();
        DataNode node = getNode(pathString);
        if (node == null) {
            return;
        }
        String[] children;
        DataNode nodeCopy;
        synchronized (node) {
            StatPersisted statCopy = new StatPersisted();
            copyStatPersisted(node.stat, statCopy);
            //we do not need to make a copy of node.data because the contents
            //are never changed
            nodeCopy = new DataNode(node.data, node.acl, statCopy);
            children = node.getChildren().toArray(new String[0]);
        }
        serializeNodeData(oa, pathString, nodeCopy);
        path.append('/');
        int off = path.length();
        for (String child : children) {
            // Since this is single buffer being reused, we need to truncate the previous bytes of string.
            path.delete(off, Integer.MAX_VALUE);
            path.append(child);
            serializeNode(oa, path);
        }
    }

    // visible for test
    public void serializeNodeData(OutputArchive oa, String path, DataNode node) throws IOException {
        oa.writeString(path, "path");
        oa.writeRecord(node, "node");
    }

    public void serializeAcls(OutputArchive oa) throws IOException {
        aclCache.serialize(oa);
    }

    public void serializeNodes(OutputArchive oa) throws IOException {
        serializeNode(oa, new StringBuilder());
        // / marks end of stream
        // we need to check if clear had been called in between the snapshot.
        if (root != null) {
            oa.writeString("/", "path");
        }
    }

    public void serialize(OutputArchive oa, String tag) throws IOException {
        serializeAcls(oa);
        serializeNodes(oa);
    }

    public void deserialize(InputArchive ia, String tag) throws IOException {
        aclCache.deserialize(ia);
        nodes.clear();
        pTrie.clear();
        nodeDataSize.set(0);
        String path = ia.readString("path");
        while (!"/".equals(path)) {
            DataNode node = new DataNode();
            ia.readRecord(node, "node");
            nodes.put(path, node);
            synchronized (node) {
                aclCache.addUsage(node.acl);
            }
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash == -1) {
                root = node;
            } else {
                String parentPath = path.substring(0, lastSlash);
                DataNode parent = nodes.get(parentPath);
                if (parent == null) {
                    throw new IOException(
                            "Invalid Datatree, unable to find parent " + parentPath + " of path " + path);
                }
                parent.addChild(path.substring(lastSlash + 1));
                long owner = node.stat.getEphemeralOwner();
                EphemeralType ephemeralType = EphemeralType.get(owner);
                if (ephemeralType == EphemeralType.CONTAINER) {
                    containers.add(path);
                } else if (ephemeralType == EphemeralType.TTL) {
                    ttls.add(path);
                } else if (owner != 0) {
                    HashSet<String> list = ephemerals.computeIfAbsent(owner, k -> new HashSet<>());
                    list.add(path);
                }
            }
            path = ia.readString("path");
        }
        // have counted digest for root node with "", ignore here to avoid
        // counting twice for root node
        nodes.putWithoutDigest("/", root);

        nodeDataSize.set(approximateDataSize());

        // we are done with deserializing the datatree
        // update the quotas - create path trie
        // and also update the stat nodes
        setupQuota();

        aclCache.purgeUnused();
    }

    /**
     * Summary of the watches on the datatree.
     * @param writer the output to write to
     */
    public synchronized void dumpWatchesSummary(PrintWriter writer) {
        writer.print(dataWatches.toString());
    }

    /**
     * Write a text dump of all the watches on the datatree.
     * Warning, this is expensive, use sparingly!
     * @param writer the output to write to
     */
    public synchronized void dumpWatches(PrintWriter writer, boolean byPath) {
        dataWatches.dumpWatches(writer, byPath);
    }

    /**
     * Returns a watch report.
     *
     * @return watch report
     * @see WatchesReport
     */
    public synchronized WatchesReport getWatches() {
        return dataWatches.getWatches();
    }

    /**
     * Returns a watch report by path.
     *
     * @return watch report
     * @see WatchesPathReport
     */
    public synchronized WatchesPathReport getWatchesByPath() {
        return dataWatches.getWatchesByPath();
    }

    /**
     * Returns a watch summary.
     *
     * @return watch summary
     * @see WatchesSummary
     */
    public synchronized WatchesSummary getWatchesSummary() {
        return dataWatches.getWatchesSummary();
    }

    /**
     * Write a text dump of all the ephemerals in the datatree.
     * @param writer the output to write to
     */
    public void dumpEphemerals(PrintWriter writer) {
        writer.println("Sessions with Ephemerals (" + ephemerals.keySet().size() + "):");
        for (Entry<Long, HashSet<String>> entry : ephemerals.entrySet()) {
            writer.print("0x" + Long.toHexString(entry.getKey()));
            writer.println(":");
            Set<String> tmp = entry.getValue();
            if (tmp != null) {
                synchronized (tmp) {
                    for (String path : tmp) {
                        writer.println("\t" + path);
                    }
                }
            }
        }
    }

    public void shutdownWatcher() {
        dataWatches.shutdown();
        childWatches.shutdown();
    }

    /**
     * Returns a mapping of session ID to ephemeral znodes.
     *
     * @return map of session ID to sets of ephemeral znodes
     */
    public Map<Long, Set<String>> getEphemerals() {
        Map<Long, Set<String>> ephemeralsCopy = new HashMap<>();
        for (Entry<Long, HashSet<String>> e : ephemerals.entrySet()) {
            synchronized (e.getValue()) {
                ephemeralsCopy.put(e.getKey(), new HashSet<>(e.getValue()));
            }
        }
        return ephemeralsCopy;
    }

    public void removeCnxn(Watcher watcher) {
        dataWatches.removeWatcher(watcher);
        childWatches.removeWatcher(watcher);
    }

    public void setWatches(long relativeZxid, List<String> dataWatches, List<String> existWatches, List<String> childWatches,
                           List<String> persistentWatches, List<String> persistentRecursiveWatches, Watcher watcher) {
        for (String path : dataWatches) {
            DataNode node = getNode(path);
            if (node == null) {
                watcher.process(new WatchedEvent(EventType.NodeDeleted, KeeperState.SyncConnected, path));
            } else if (node.stat.getMzxid() > relativeZxid) {
                watcher.process(new WatchedEvent(EventType.NodeDataChanged, KeeperState.SyncConnected, path));
            } else {
                this.dataWatches.addWatch(path, watcher);
            }
        }
        for (String path : existWatches) {
            DataNode node = getNode(path);
            if (node != null) {
                watcher.process(new WatchedEvent(EventType.NodeCreated, KeeperState.SyncConnected, path));
            } else {
                this.dataWatches.addWatch(path, watcher);
            }
        }
        for (String path : childWatches) {
            DataNode node = getNode(path);
            if (node == null) {
                watcher.process(new WatchedEvent(EventType.NodeDeleted, KeeperState.SyncConnected, path));
            } else if (node.stat.getPzxid() > relativeZxid) {
                watcher.process(new WatchedEvent(EventType.NodeChildrenChanged, KeeperState.SyncConnected, path));
            } else {
                this.childWatches.addWatch(path, watcher);
            }
        }
        for (String path : persistentWatches) {
            this.childWatches.addWatch(path, watcher, WatcherMode.PERSISTENT);
            this.dataWatches.addWatch(path, watcher, WatcherMode.PERSISTENT);
        }
        for (String path : persistentRecursiveWatches) {
            this.dataWatches.addWatch(path, watcher, WatcherMode.PERSISTENT_RECURSIVE);
        }
    }

    /**
     * This method sets the Cversion and Pzxid for the specified node to the
     * values passed as arguments. The values are modified only if newCversion
     * is greater than the current Cversion. A NoNodeException is thrown if
     * a znode for the specified path is not found.
     *
     * @param path
     *     Full path to the znode whose Cversion needs to be modified.
     *     A "/" at the end of the path is ignored.
     * @param newCversion
     *     Value to be assigned to Cversion
     * @param zxid
     *     Value to be assigned to Pzxid
     * @throws NoNodeException
     *     If znode not found.
     **/
    public void setCversionPzxid(String path, int newCversion, long zxid) throws NoNodeException {
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        DataNode node = nodes.get(path);
        if (node == null) {
            throw new NoNodeException(path);
        }
        synchronized (node) {
            if (newCversion == -1) {
                newCversion = node.stat.getCversion() + 1;
            }
            if (newCversion > node.stat.getCversion()) {
                nodes.preChange(path, node);
                node.stat.setCversion(newCversion);
                node.stat.setPzxid(zxid);
                nodes.postChange(path, node);
            }
        }
    }

    public boolean containsWatcher(String path, WatcherType type, Watcher watcher) {
        boolean containsWatcher = false;
        switch (type) {
        case Children:
            containsWatcher = this.childWatches.containsWatcher(path, watcher, WatcherMode.STANDARD);
            break;
        case Data:
            containsWatcher = this.dataWatches.containsWatcher(path, watcher, WatcherMode.STANDARD);
            break;
        case Persistent:
            containsWatcher = this.dataWatches.containsWatcher(path, watcher, WatcherMode.PERSISTENT);
            break;
        case PersistentRecursive:
            containsWatcher = this.dataWatches.containsWatcher(path, watcher, WatcherMode.PERSISTENT_RECURSIVE);
            break;
        case Any:
            if (this.childWatches.containsWatcher(path, watcher, null)) {
                containsWatcher = true;
            } else if (this.dataWatches.containsWatcher(path, watcher, null)) {
                containsWatcher = true;
            }
            break;
        }
        return containsWatcher;
    }

    public boolean removeWatch(String path, WatcherType type, Watcher watcher) {
        boolean removed = false;
        switch (type) {
        case Children:
            removed = this.childWatches.removeWatcher(path, watcher, WatcherMode.STANDARD);
            break;
        case Data:
            removed = this.dataWatches.removeWatcher(path, watcher, WatcherMode.STANDARD);
            break;
        case Persistent:
            if (this.childWatches.removeWatcher(path, watcher, WatcherMode.PERSISTENT)) {
                removed = true;
            }
            if (this.dataWatches.removeWatcher(path, watcher, WatcherMode.PERSISTENT)) {
                removed = true;
            }
            break;
        case PersistentRecursive:
            removed = this.dataWatches.removeWatcher(path, watcher, WatcherMode.PERSISTENT_RECURSIVE);
            break;
        case Any:
            if (this.childWatches.removeWatcher(path, watcher, null)) {
                removed = true;
            }
            if (this.dataWatches.removeWatcher(path, watcher, null)) {
                removed = true;
            }
            break;
        }
        return removed;
    }

    // visible for testing
    public ReferenceCountedACLCache getReferenceCountedAclCache() {
        return aclCache;
    }

    private void updateReadStat(String path, long bytes) {
        final String namespace = PathUtils.getTopNamespace(path);
        if (namespace == null) {
            return;
        }
        long totalBytes = path.length() + bytes + STAT_OVERHEAD_BYTES;
        ServerMetrics.getMetrics().READ_PER_NAMESPACE.add(namespace, totalBytes);
    }

    private void updateWriteStat(String path, long bytes) {
        final String namespace = PathUtils.getTopNamespace(path);
        if (namespace == null) {
            return;
        }
        ServerMetrics.getMetrics().WRITE_PER_NAMESPACE.add(namespace, path.length() + bytes);
    }

    /**
     * Add the digest to the historical list, and update the latest zxid digest.
     */
    private void logZxidDigest(long zxid, long digest) {
        ZxidDigest zxidDigest = new ZxidDigest(zxid, digestCalculator.getDigestVersion(), digest);
        lastProcessedZxidDigest = zxidDigest;
        if (zxidDigest.zxid % DIGEST_LOG_INTERVAL == 0) {
            synchronized (digestLog) {
                digestLog.add(zxidDigest);
                if (digestLog.size() > DIGEST_LOG_LIMIT) {
                    digestLog.poll();
                }
            }
        }
    }

    /**
     * Serializing the digest to snapshot, this is done after the data tree
     * is being serialized, so when we replay the txns, and it hits this zxid
     * we know we should be in a non-fuzzy state, and have the same digest.
     *
     * @param oa the output stream to write to
     * @return true if the digest is serialized successfully
     */
    public boolean serializeZxidDigest(OutputArchive oa) throws IOException {
        if (!ZooKeeperServer.isDigestEnabled()) {
            return false;
        }

        ZxidDigest zxidDigest = lastProcessedZxidDigest;
        if (zxidDigest == null) {
            // write an empty digest
            zxidDigest = new ZxidDigest();
        }
        zxidDigest.serialize(oa);
        return true;
    }

    /**
     * Deserializing the zxid digest from the input stream and update the
     * digestFromLoadedSnapshot.
     *
     * @param ia the input stream to read from
     * @param startZxidOfSnapshot the zxid of snapshot file
     * @return the true if it deserialized successfully
     */
    public boolean deserializeZxidDigest(InputArchive ia, long startZxidOfSnapshot) throws IOException {
        if (!ZooKeeperServer.isDigestEnabled()) {
            return false;
        }

        try {
            ZxidDigest zxidDigest = new ZxidDigest();
            zxidDigest.deserialize(ia);
            if (zxidDigest.zxid > 0) {
                digestFromLoadedSnapshot = zxidDigest;
                LOG.info("The digest in the snapshot has digest version of {}, "
                        + "with zxid as 0x{}, and digest value as {}",
                        digestFromLoadedSnapshot.digestVersion,
                        Long.toHexString(digestFromLoadedSnapshot.zxid),
                        digestFromLoadedSnapshot.digest);
            } else {
                digestFromLoadedSnapshot = null;
                LOG.info("The digest value is empty in snapshot");
            }

            // There is possibility that the start zxid of a snapshot might
            // be larger than the digest zxid in snapshot.
            //
            // Known cases:
            //
            // The new leader set the last processed zxid to be the new
            // epoch + 0, which is not mapping to any txn, and it uses
            // this to take snapshot, which is possible if we don't
            // clean database before switching to LOOKING. In this case
            // the currentZxidDigest will be the zxid of last epoch, and
            // it's smaller than the zxid of the snapshot file.
            //
            // It's safe to reset the targetZxidDigest to null and start
            // to compare digest when replaying the first txn, since it's
            // a non-fuzzy snapshot.
            if (digestFromLoadedSnapshot != null && digestFromLoadedSnapshot.zxid < startZxidOfSnapshot) {
                LOG.info("The zxid of snapshot digest 0x{} is smaller "
                        + "than the known snapshot highest zxid, the snapshot "
                        + "started with zxid 0x{}. It will be invalid to use "
                        + "this snapshot digest associated with this zxid, will "
                        + "ignore comparing it.", Long.toHexString(digestFromLoadedSnapshot.zxid),
                        Long.toHexString(startZxidOfSnapshot));
                digestFromLoadedSnapshot = null;
            }

            return true;
        } catch (EOFException e) {
            LOG.warn("Got EOF exception while reading the digest, likely due to the reading an older snapshot.");
            return false;
        }
    }

    /**
     * Serializes the lastProcessedZxid so we can get it from snapshot instead the snapshot file name.
     * This is needed for performing snapshot and restore via admin server commands.
     *
     * @param oa the output stream to write to
     * @return true if the lastProcessedZxid is serialized successfully, otherwise false
     * @throws IOException if there is an I/O error
     */
    public boolean serializeLastProcessedZxid(final OutputArchive oa) throws IOException {
        if (!ZooKeeperServer.isSerializeLastProcessedZxidEnabled()) {
            return false;
        }
        oa.writeLong(lastProcessedZxid, "lastZxid");
        return true;
    }

    /**
     * Deserializes the lastProcessedZxid from the input stream and updates the lastProcessedZxid field.
     *
     * @param ia the input stream to read from
     * @return true if lastProcessedZxid is deserialized successfully, otherwise false
     * @throws IOException if there is an I/O error
     */
    public boolean deserializeLastProcessedZxid(final InputArchive ia)  throws IOException {
        if (!ZooKeeperServer.isSerializeLastProcessedZxidEnabled()) {
            return false;
        }
        try {
            lastProcessedZxid = ia.readLong("lastZxid");
        } catch (final EOFException e) {
            LOG.warn("Got EOFException while reading the last processed zxid, likely due to reading an older snapshot.");
            return false;
        }
        return true;
    }

    /**
     * Compares the actual tree's digest with that in the snapshot.
     * Resets digestFromLoadedSnapshot after comparison.
     *
     * @param zxid zxid
     */
    public void compareSnapshotDigests(long zxid) {
        if (zxid == digestFromLoadedSnapshot.zxid) {
            if (digestCalculator.getDigestVersion() != digestFromLoadedSnapshot.digestVersion) {
                LOG.info(
                    "Digest version changed, local: {}, new: {}, skip comparing digest now.",
                    digestFromLoadedSnapshot.digestVersion,
                    digestCalculator.getDigestVersion());
                digestFromLoadedSnapshot = null;
                return;
            }
            if (getTreeDigest() != digestFromLoadedSnapshot.getDigest()) {
                reportDigestMismatch(zxid);
            }
            digestFromLoadedSnapshot = null;
        } else if (digestFromLoadedSnapshot.zxid != 0 && zxid > digestFromLoadedSnapshot.zxid) {
            RATE_LOGGER.rateLimitLog(
                    "The txn 0x{} of snapshot digest does not exist.",
                    Long.toHexString(digestFromLoadedSnapshot.zxid));
        }
    }

    /**
     * Compares the digest of the tree with the digest present in transaction digest.
     * If there is any error, logs and alerts the watchers.
     *
     * @param header transaction header being applied
     * @param txn    transaction
     * @param digest transaction digest
     *
     * @return false if digest in the txn doesn't match what we have now in the data tree
     */
    public boolean compareDigest(TxnHeader header, Record txn, TxnDigest digest) {
        long zxid = header.getZxid();

        if (!ZooKeeperServer.isDigestEnabled() || digest == null) {
            return true;
        }
        // do not compare digest if we're still in fuzzy state
        if (digestFromLoadedSnapshot != null) {
            return true;
        }
        // do not compare digest if there is digest version change
        if (digestCalculator.getDigestVersion() != digest.getVersion()) {
            RATE_LOGGER.rateLimitLog("Digest version not the same on zxid.", String.valueOf(zxid));
            return true;
        }

        long logDigest = digest.getTreeDigest();
        long actualDigest = getTreeDigest();
        if (logDigest != actualDigest) {
            reportDigestMismatch(zxid);
            LOG.debug("Digest in log: {}, actual tree: {}", logDigest, actualDigest);
            if (firstMismatchTxn) {
                LOG.error(
                        "First digest mismatch on txn: {}, {}, expected digest is {}, actual digest is {}, ",
                        header, txn, digest, actualDigest);
                firstMismatchTxn = false;
            }
            return false;
        } else {
            RATE_LOGGER.flush();
            LOG.debug(
                    "Digests are matching for Zxid: {}, Digest in log and actual tree: {}",
                    Long.toHexString(zxid), logDigest);
            return true;
        }
    }

    /**
     * Reports any mismatch in the transaction digest.
     * @param zxid zxid for which the error is being reported.
     */
    public void reportDigestMismatch(long zxid) {
        ServerMetrics.getMetrics().DIGEST_MISMATCHES_COUNT.add(1);
        RATE_LOGGER.rateLimitLog("Digests are not matching. Value is Zxid.", String.valueOf(zxid));

        for (DigestWatcher watcher : digestWatchers) {
            watcher.process(zxid);
        }
    }

    public long getTreeDigest() {
        return nodes.getDigest();
    }

    public ZxidDigest getLastProcessedZxidDigest() {
        return lastProcessedZxidDigest;
    }

    public ZxidDigest getDigestFromLoadedSnapshot() {
        return digestFromLoadedSnapshot;
    }

    /**
     * Add digest mismatch event handler.
     *
     * @param digestWatcher the handler to add
     */
    public void addDigestWatcher(DigestWatcher digestWatcher) {
        digestWatchers.add(digestWatcher);
    }

    /**
     * Return all the digests in the historical digest list.
     */
    public List<ZxidDigest> getDigestLog() {
        synchronized (digestLog) {
            // Return a copy of current digest log
            return new LinkedList<>(digestLog);
        }
    }

    /**
     * A helper class to maintain the digest meta associated with specific zxid.
     */
    public class ZxidDigest {

        long zxid;
        // the digest value associated with this zxid
        long digest;
        // the version when the digest was calculated
        int digestVersion;

        ZxidDigest() {
            this(0, digestCalculator.getDigestVersion(), 0);
        }

        ZxidDigest(long zxid, int digestVersion, long digest) {
            this.zxid = zxid;
            this.digestVersion = digestVersion;
            this.digest = digest;
        }

        public void serialize(OutputArchive oa) throws IOException {
            oa.writeLong(zxid, "zxid");
            oa.writeInt(digestVersion, "digestVersion");
            oa.writeLong(digest, "digest");
        }

        public void deserialize(InputArchive ia) throws IOException {
            zxid = ia.readLong("zxid");
            digestVersion = ia.readInt("digestVersion");
            // the old version is using hex string as the digest
            if (digestVersion < 2) {
                String d = ia.readString("digest");
                if (d != null) {
                    digest = Long.parseLong(d, 16);
                }
            } else {
                digest = ia.readLong("digest");
            }
        }

        public long getZxid() {
            return zxid;
        }

        public int getDigestVersion() {
            return digestVersion;
        }

        public long getDigest() {
            return digest;
        }

    }

    /**
     * Create a node stat from the given params.
     *
     * @param zxid the zxid associated with the txn
     * @param time the time when the txn is created
     * @param ephemeralOwner the owner if the node is an ephemeral
     * @return the stat
     */
    public static StatPersisted createStat(long zxid, long time, long ephemeralOwner) {
        StatPersisted stat = new StatPersisted();
        stat.setCtime(time);
        stat.setMtime(time);
        stat.setCzxid(zxid);
        stat.setMzxid(zxid);
        stat.setPzxid(zxid);
        stat.setVersion(0);
        stat.setAversion(0);
        stat.setEphemeralOwner(ephemeralOwner);
        return stat;
    }

    // for test only
    static StatPersisted createStat(int version) {
        StatPersisted stat = new StatPersisted();
        stat.setCtime(0);
        stat.setMtime(0);
        stat.setCzxid(0);
        stat.setMzxid(0);
        stat.setPzxid(0);
        stat.setVersion(version);
        stat.setAversion(0);
        stat.setEphemeralOwner(0);
        return stat;
    }
}
