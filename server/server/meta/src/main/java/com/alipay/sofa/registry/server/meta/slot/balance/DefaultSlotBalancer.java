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
package com.alipay.sofa.registry.server.meta.slot.balance;

import com.alipay.sofa.registry.common.model.Tuple;
import com.alipay.sofa.registry.common.model.slot.DataNodeSlot;
import com.alipay.sofa.registry.common.model.slot.SlotTable;
import com.alipay.sofa.registry.exception.SofaRegistryRuntimeException;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.server.meta.slot.SlotBalancer;
import com.alipay.sofa.registry.server.meta.slot.util.builder.SlotTableBuilder;
import com.alipay.sofa.registry.server.meta.slot.util.comparator.Comparators;
import com.alipay.sofa.registry.common.model.Triple;
import com.alipay.sofa.registry.util.MathUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;

import java.util.*;

/**
 * @author chen.zhu
 * <p>
 * Jan 15, 2021
 */
public class DefaultSlotBalancer implements SlotBalancer {

    private static final Logger      logger        = LoggerFactory
                                                       .getLogger(DefaultSlotBalancer.class);

    private final Set<String>        currentDataServers;

    protected final SlotTableBuilder slotTableBuilder;
    private final BalancePolicy      balancePolicy = new NaiveBalancePolicy();
    private final int                slotNum;
    private final int                slotReplicas;

    public DefaultSlotBalancer(SlotTableBuilder slotTableBuilder,
                               Collection<String> currentDataServers) {
        this.currentDataServers = Collections.unmodifiableSet(Sets.newTreeSet(currentDataServers));
        this.slotTableBuilder = slotTableBuilder;
        this.slotNum = slotTableBuilder.getSlotNums();
        this.slotReplicas = slotTableBuilder.getSlotReplicas();
    }

    @Override
    public SlotTable balance() {
        if (currentDataServers.isEmpty()) {
            logger.error("[no available data-servers] quit");
            throw new SofaRegistryRuntimeException(
                "no available data-servers for slot-table reassignment");
        }
        if (slotReplicas < 2) {
            logger.warn("[balance] slot replica[{}] means no followers, balance leader only",
                slotTableBuilder.getSlotReplicas());
            return new LeaderOnlyBalancer(slotTableBuilder, currentDataServers).balance();
        }
        // TODO balance leader need to check the slot.migrating
        if (balanceLeaderSlots()) {
            logger.info("[balanceLeaderSlots] end");
            slotTableBuilder.incrEpoch();
            return slotTableBuilder.build();
        }
        if (balanceHighFollowerSlots()) {
            logger.info("[balanceHighFollowerSlots] end");
            slotTableBuilder.incrEpoch();
            return slotTableBuilder.build();
        }
        if (balanceLowFollowerSlots()) {
            logger.info("[balanceLowFollowerSlots] end");
            slotTableBuilder.incrEpoch();
            return slotTableBuilder.build();
        }
        // check the low watermark leader, the follower has balanced
        // just upgrade the followers in low data server
        if (balanceLowLeaders()) {
            logger.info("[balanceLowLeaders] end");
            slotTableBuilder.incrEpoch();
            return slotTableBuilder.build();
        }
        logger.info("[balance] do nothing");
        return null;
    }

    private boolean balanceLeaderSlots() {
        // ceil avg, find the high water mark
        final int leaderCeilAvg = MathUtils.divideCeil(slotNum, currentDataServers.size());
        if (upgradeHighLeaders(leaderCeilAvg)) {
            return true;
        }
        if (migrateHighLeaders(leaderCeilAvg)) {
            return true;
        }
        return false;
    }

    private boolean upgradeHighLeaders(int ceilAvg) {
        // smoothly, find the dataNode which owners the target slot's follower
        // and upgrade the follower to leader
        final int maxMove = balancePolicy.getMaxMoveLeaderSlots();
        final int threshold = balancePolicy.getHighWaterMarkSlotLeaderNums(ceilAvg);
        int balanced = 0;
        Set<String> notSatisfies = Sets.newHashSet();

        while (balanced < maxMove) {
            // 1. find the dataNode which has leaders more than high water mark
            //    and sorted by leaders.num desc
            final List<String> highDataServers = findDataServersLeaderHighWaterMark(threshold);
            if (highDataServers.isEmpty()) {
                break;
            }
            // could not found any follower to upgrade
            if (notSatisfies.containsAll(highDataServers)) {
                logger.info("[upgradeHighLeaders] could not found followers to upgrade for {}",
                    highDataServers);
                break;
            }
            // 2. find the dataNode which could own a new leader
            // exclude the high
            final Set<String> excludes = Sets.newHashSet(highDataServers);
            // exclude the dataNode which could not add any leader
            excludes.addAll(findDataServersLeaderHighWaterMark(threshold - 1));
            for (String highDataServer : highDataServers) {
                if (notSatisfies.contains(highDataServer)) {
                    continue;
                }
                Tuple<String, Integer> selected = selectFollower4LeaderUpgradeOut(highDataServer,
                    excludes);
                if (selected == null) {
                    notSatisfies.add(highDataServer);
                    continue;
                }
                final int slotId = selected.o2;
                final String newLeaderDataServer = selected.o1;
                slotTableBuilder.replaceLeader(slotId, newLeaderDataServer);
                logger.info("[upgradeHighLeaders] slotId={} leader balance from {} to {}", slotId,
                    highDataServer, newLeaderDataServer);
                balanced++;
                break;
            }
        }
        return balanced != 0;
    }

    private boolean migrateHighLeaders(int ceilAvg) {
        // could not found the follower to upgrade, migrate follower first
        final int maxMove = balancePolicy.getMaxMoveFollowerSlots();
        final int threshold = balancePolicy.getHighWaterMarkSlotLeaderNums(ceilAvg);

        // 1. find the dataNode which has leaders more than high water mark
        //    and sorted by leaders.num desc
        final List<String> highDataServers = findDataServersLeaderHighWaterMark(threshold);
        if (highDataServers.isEmpty()) {
            return false;
        }
        // 2. find the dataNode which could own a new leader
        // exclude the high
        final Set<String> excludes = Sets.newHashSet(highDataServers);
        // exclude the dataNode which could not add any leader
        excludes.addAll(findDataServersLeaderHighWaterMark(threshold - 1));
        int balanced = 0;
        final Set<String> newFollowerDataServers = Sets.newHashSet();
        // only balance highDataServer once at one round, avoid the follower moves multi times
        for (String highDataServer : highDataServers) {
            Triple<String, Integer, String> selected = selectFollower4LeaderMigrate(highDataServer,
                excludes, newFollowerDataServers);
            if (selected == null) {
                logger.warn(
                    "[migrateHighLeaders] could not find dataServer to migrate follower for {}",
                    highDataServer);
                continue;
            }
            final String oldFollower = selected.getFirst();
            final int slotId = selected.getMiddle();
            final String newFollower = selected.getLast();
            slotTableBuilder.removeFollower(slotId, oldFollower);
            slotTableBuilder.addFollower(slotId, newFollower);
            newFollowerDataServers.add(newFollower);
            logger.info("[migrateHighLeaders] slotId={}, follower balance from {} to {}", slotId,
                oldFollower, newFollower);
            balanced++;
            if (balanced >= maxMove) {
                break;
            }
        }
        return balanced != 0;
    }

    private boolean balanceLowLeaders() {
        final int leaderFloorAvg = Math.floorDiv(slotNum, currentDataServers.size());
        final int maxMove = balancePolicy.getMaxMoveLeaderSlots();
        final int threshold = balancePolicy.getLowWaterMarkSlotLeaderNums(leaderFloorAvg);
        int balanced = 0;
        Set<String> notSatisfies = Sets.newHashSet();

        while (balanced < maxMove) {
            // 1. find the dataNode which has leaders less than low water mark
            //    and sorted by leaders.num asc
            final List<String> lowDataServers = findDataServersLeaderLowWaterMark(threshold);
            if (lowDataServers.isEmpty()) {
                break;
            }
            // could not found any follower to upgrade
            if (notSatisfies.containsAll(lowDataServers)) {
                logger.info("[upgradeLowLeaders] could not found followers to upgrade for {}",
                    lowDataServers);
                break;
            }
            // 2. find the dataNode which could not remove a leader
            // exclude the low
            final Set<String> excludes = Sets.newHashSet(lowDataServers);
            // exclude the dataNode which could not remove any leader
            excludes.addAll(findDataServersLeaderLowWaterMark(threshold + 1));
            for (String lowDataServer : lowDataServers) {
                if (notSatisfies.contains(lowDataServer)) {
                    continue;
                }
                Tuple<String, Integer> selected = selectFollower4LeaderUpgradeIn(lowDataServer,
                    excludes);
                if (selected == null) {
                    notSatisfies.add(lowDataServer);
                    continue;
                }
                final int slotId = selected.o2;
                final String oldLeaderDataServer = selected.o1;
                final String replaceLeader = slotTableBuilder.replaceLeader(slotId, lowDataServer);
                if (!StringUtils.equals(oldLeaderDataServer, replaceLeader)) {
                    logger
                        .error(
                            "[upgradeLowLeaders] conflict leader, slotId={} leader balance from {}/{} to {}",
                            slotId, oldLeaderDataServer, replaceLeader, lowDataServer);
                    throw new SofaRegistryRuntimeException(String.format(
                        "upgradeLowLeaders, conflict leader=%d of %s and %s", slotId,
                        oldLeaderDataServer, replaceLeader));
                }
                logger.info("[upgradeLowLeaders] slotId={} leader balance from {} to {}", slotId,
                    oldLeaderDataServer, lowDataServer);
                balanced++;
                break;
            }
        }
        return balanced != 0;
    }

    private Triple<String, Integer, String> selectFollower4LeaderMigrate(String leaderDataServer, Set<String> excludes,
                                                                         Set<String> newFollowerDataServers) {
        final DataNodeSlot dataNodeSlot = slotTableBuilder.getDataNodeSlot(leaderDataServer);
        Set<Integer> leaderSlots = dataNodeSlot.getLeaders();
        Map<String, List<Integer>> dataServersWithFollowers = Maps.newHashMap();
        for (int slot : leaderSlots) {
            List<String> followerDataServers = slotTableBuilder.getDataServersOwnsFollower(slot);
            for (String followerDataServer : followerDataServers) {
                if (newFollowerDataServers.contains(followerDataServer)) {
                    // the followerDataServer contains move in follower, could not be move out candidates
                    continue;
                }
                List<Integer> followerSlots = dataServersWithFollowers.computeIfAbsent(followerDataServer,
                        k -> Lists.newArrayList());
                followerSlots.add(slot);
            }
        }
        logger.info("[LeaderMigrate] {} owns leader slots={}, slotIds={}, migrate candidates {}, newFollowers={}",
                leaderDataServer, leaderSlots.size(), leaderSlots, dataServersWithFollowers, newFollowerDataServers);
        // sort the dataServer by follower.num desc
        List<String> migrateDataServers = Lists.newArrayList(dataServersWithFollowers.keySet());
        migrateDataServers.sort(Comparators.mostFollowersFirst(slotTableBuilder));
        for (String migrateDataServer : migrateDataServers) {
            final List<Integer> selectedFollowers = dataServersWithFollowers.get(migrateDataServer);
            for (Integer selectedFollower : selectedFollowers) {
                // chose the dataServer which own least leaders
                List<String> candidates = getCandidateDataServers(excludes,
                        Comparators.leastLeadersFirst(slotTableBuilder), currentDataServers);
                for (String candidate : candidates) {
                    if (candidate.equals(migrateDataServer)) {
                        // the same, skip
                        continue;
                    }
                    DataNodeSlot candidateDataNodeSlot = slotTableBuilder.getDataNodeSlot(candidate);
                    if (candidateDataNodeSlot.containsFollower(selectedFollower)) {
                        logger.error("[LeaderMigrate] slotId={}, follower conflict with migrate from {} to {}",
                                selectedFollower, migrateDataServer, candidateDataNodeSlot);
                        continue;
                    }
                    return Triple.from(migrateDataServer, selectedFollower, candidate);
                }
            }

        }
        return null;
    }

    private Tuple<String, Integer> selectFollower4LeaderUpgradeOut(String leaderDataServer, Set<String> excludes) {
        final DataNodeSlot dataNodeSlot = slotTableBuilder.getDataNodeSlot(leaderDataServer);
        Set<Integer> leaderSlots = dataNodeSlot.getLeaders();
        Map<String, List<Integer>> dataServers2Followers = Maps.newHashMap();
        for (int slot : leaderSlots) {
            List<String> followerDataServers = slotTableBuilder.getDataServersOwnsFollower(slot);
            followerDataServers = getCandidateDataServers(excludes, null, followerDataServers);
            for (String followerDataServer : followerDataServers) {
                List<Integer> followerSlots = dataServers2Followers.computeIfAbsent(followerDataServer,
                        k -> Lists.newArrayList());
                followerSlots.add(slot);
            }
        }
        if (dataServers2Followers.isEmpty()) {
            logger.info("[LeaderUpgradeOut] {} owns leader slots={}, no dataServers could be upgrade, slotId={}",
                    leaderDataServer, leaderSlots.size(), leaderSlots);
            return null;
        } else {
            logger.info("[LeaderUpgradeOut] {} owns leader slots={}, slotIds={}, upgrade candidates {}",
                    leaderDataServer, leaderSlots.size(), leaderSlots, dataServers2Followers);
        }
        // sort the dataServer by leaders.num asc
        List<String> dataServers = Lists.newArrayList(dataServers2Followers.keySet());
        dataServers.sort(Comparators.leastLeadersFirst(slotTableBuilder));
        final String selectedDataServer = dataServers.get(0);
        List<Integer> followers = dataServers2Followers.get(selectedDataServer);
        return Tuple.of(selectedDataServer, followers.get(0));
    }

    private Tuple<String, Integer> selectFollower4LeaderUpgradeIn(String followerDataServer, Set<String> excludes) {
        final DataNodeSlot dataNodeSlot = slotTableBuilder.getDataNodeSlot(followerDataServer);
        Set<Integer> followerSlots = dataNodeSlot.getFollowers();
        Map<String, List<Integer>> dataServers2Leaders = Maps.newHashMap();
        for (int slot : followerSlots) {
            final String leaderDataServers = slotTableBuilder.getDataServersOwnsLeader(slot);
            if (StringUtils.isBlank(leaderDataServers)) {
                // no leader, should not happen
                logger.error("[LeaderUpgradeIn] no leader for slotId={} in {}", slot, followerDataServer);
                continue;
            }
            if (excludes.contains(leaderDataServers)) {
                final DataNodeSlot leaderDataNodeSlot = slotTableBuilder.getDataNodeSlot(leaderDataServers);
                logger.info("[LeaderUpgradeIn] {} not owns enough leader to downgrade, leaderSize={}, leaders={}",
                        leaderDataServers, leaderDataNodeSlot.getLeaders().size(), leaderDataNodeSlot.getLeaders());
                continue;
            }

            List<Integer> leaders = dataServers2Leaders.computeIfAbsent(leaderDataServers, k -> Lists.newArrayList());
            leaders.add(slot);
        }
        if (dataServers2Leaders.isEmpty()) {
            logger.info("[LeaderUpgradeIn] {} owns followerSize={}, no dataServers could be downgrade, slotId={}",
                    followerDataServer, followerSlots.size(), followerSlots);
            return null;
        } else {
            logger.info("[LeaderUpgradeIn] {} owns followerSize={}, slotIds={}, downgrade candidates {}",
                    followerDataServer, followerSlots.size(), followerSlots, dataServers2Leaders);
        }
        // sort the dataServer by leaders.num asc
        List<String> dataServers = Lists.newArrayList(dataServers2Leaders.keySet());
        dataServers.sort(Comparators.mostLeadersFirst(slotTableBuilder));
        final String selectedDataServer = dataServers.get(0);
        List<Integer> leaders = dataServers2Leaders.get(selectedDataServer);
        return Tuple.of(selectedDataServer, leaders.get(0));
    }

    private List<String> findDataServersLeaderHighWaterMark(int threshold) {
        List<DataNodeSlot> dataNodeSlots = slotTableBuilder.getDataNodeSlotsLeaderBeyond(threshold);
        List<String> dataServers = DataNodeSlot.collectDataNodes(dataNodeSlots);
        dataServers.sort(Comparators.mostLeadersFirst(slotTableBuilder));
        logger.info("[LeaderHighWaterMark] threshold={}, dataServers={}", threshold, dataServers);
        return dataServers;
    }

    private List<String> findDataServersLeaderLowWaterMark(int threshold) {
        List<DataNodeSlot> dataNodeSlots = slotTableBuilder.getDataNodeSlotsLeaderBelow(threshold);
        List<String> dataServers = DataNodeSlot.collectDataNodes(dataNodeSlots);
        dataServers.sort(Comparators.leastLeadersFirst(slotTableBuilder));
        logger.info("[LeaderLowWaterMark] threshold={}, dataServers={}", threshold, dataServers);
        return dataServers;
    }

    private List<String> findDataServersFollowerHighWaterMark(int threshold) {
        List<DataNodeSlot> dataNodeSlots = slotTableBuilder
            .getDataNodeSlotsFollowerBeyond(threshold);
        List<String> dataServers = DataNodeSlot.collectDataNodes(dataNodeSlots);
        dataServers.sort(Comparators.mostFollowersFirst(slotTableBuilder));
        logger.info("[FollowerHighWaterMark] threshold={}, dataServers={}", threshold, dataServers);
        return dataServers;
    }

    private List<String> findDataServersFollowerLowWaterMark(int threshold) {
        List<DataNodeSlot> dataNodeSlots = slotTableBuilder
            .getDataNodeSlotsFollowerBelow(threshold);
        List<String> dataServers = DataNodeSlot.collectDataNodes(dataNodeSlots);
        dataServers.sort(Comparators.leastFollowersFirst(slotTableBuilder));
        logger.info("[FollowerLowWaterMark] threshold={}, dataServers={}", threshold, dataServers);
        return dataServers;
    }

    private boolean balanceHighFollowerSlots() {
        final int followerCeilAvg = MathUtils.divideCeil(slotNum * (slotReplicas - 1),
            currentDataServers.size());
        final int maxMove = balancePolicy.getMaxMoveFollowerSlots();
        final int threshold = balancePolicy.getHighWaterMarkSlotFollowerNums(followerCeilAvg);
        int balanced = 0;
        while (balanced < maxMove) {
            final List<String> highDataServers = findDataServersFollowerHighWaterMark(threshold);
            if (highDataServers.isEmpty()) {
                break;
            }

            Set<String> excludes = Sets.newHashSet(highDataServers);
            excludes.addAll(findDataServersFollowerHighWaterMark(threshold - 1));

            for (String highDataServer : highDataServers) {
                Tuple<String, Integer> selected = selectFollower4BalanceOut(highDataServer,
                    excludes);
                if (selected == null) {
                    logger.warn(
                        "[balanceHighFollowerSlots] could not find follower slot to balance: {}",
                        highDataServer);
                    continue;
                }
                final int followerSlot = selected.o2;
                final String newFollowerDataServer = selected.o1;
                slotTableBuilder.removeFollower(followerSlot, highDataServer);
                slotTableBuilder.addFollower(followerSlot, newFollowerDataServer);
                logger.info("[balanceHighFollowerSlots] balance follower slotId={} from {} to {}",
                    followerSlot, highDataServer, newFollowerDataServer);
                balanced++;
                break;
            }
        }
        return balanced != 0;
    }

    private boolean balanceLowFollowerSlots() {
        final int followerFloorAvg = Math.floorDiv(slotNum * (slotReplicas - 1),
            currentDataServers.size());
        final int maxMove = balancePolicy.getMaxMoveFollowerSlots();
        final int threshold = balancePolicy.getLowWaterMarkSlotFollowerNums(followerFloorAvg);
        int balanced = 0;
        while (balanced < maxMove) {
            final List<String> lowDataServers = findDataServersFollowerLowWaterMark(threshold);
            if (lowDataServers.isEmpty()) {
                break;
            }

            Set<String> excludes = Sets.newHashSet(lowDataServers);
            excludes.addAll(findDataServersFollowerLowWaterMark(threshold + 1));

            for (String lowDataServer : lowDataServers) {
                Tuple<String, Integer> selected = selectFollower4BalanceIn(lowDataServer, excludes);
                if (selected == null) {
                    logger.warn(
                        "[balanceLowFollowerSlots] could not find follower slot to balance: {}",
                        lowDataServer);
                    continue;
                }
                final int followerSlot = selected.o2;
                final String oldFollowerDataServer = selected.o1;
                slotTableBuilder.removeFollower(followerSlot, oldFollowerDataServer);
                slotTableBuilder.addFollower(followerSlot, lowDataServer);
                logger.info("[balanceLowFollowerSlots] balance follower slotId={} from {} to {}",
                    followerSlot, oldFollowerDataServer, lowDataServer);
                balanced++;
                break;
            }
        }
        return balanced != 0;
    }

    private Tuple<String, Integer> selectFollower4BalanceIn(String followerDataServer,
                                                            Set<String> excludes) {
        final DataNodeSlot dataNodeSlot = slotTableBuilder.getDataNodeSlot(followerDataServer);
        List<String> candidates = getCandidateDataServers(excludes,
            Comparators.mostFollowersFirst(slotTableBuilder), currentDataServers);
        logger.info("[selectFollower4BalanceIn] target={}, followerSize={}, candidates={}",
            followerDataServer, dataNodeSlot.getFollowers().size(), candidates);
        for (String candidate : candidates) {
            DataNodeSlot candidateDataNodeSlot = slotTableBuilder.getDataNodeSlot(candidate);
            Set<Integer> candidateFollowerSlots = candidateDataNodeSlot.getFollowers();
            for (int candidateFollowerSlot : candidateFollowerSlots) {
                if (dataNodeSlot.containsFollower(candidateFollowerSlot)) {
                    logger
                        .info(
                            "[selectFollower4BalanceIn] skip, target {} contains follower {}, candidate={}",
                            followerDataServer, candidateFollowerSlot, candidate);
                    continue;
                }
                if (dataNodeSlot.containsLeader(candidateFollowerSlot)) {
                    logger
                        .info(
                            "[selectFollower4BalanceIn] skip, target {} contains leader {}, candidate={}",
                            followerDataServer, candidateFollowerSlot, candidate);
                    continue;
                }
                return Tuple.of(candidate, candidateFollowerSlot);
            }
        }
        return null;
    }

    private Tuple<String, Integer> selectFollower4BalanceOut(String followerDataServer,
                                                             Set<String> excludes) {
        final DataNodeSlot dataNodeSlot = slotTableBuilder.getDataNodeSlot(followerDataServer);
        Set<Integer> followerSlots = dataNodeSlot.getFollowers();
        List<String> candidates = getCandidateDataServers(excludes,
            Comparators.leastFollowersFirst(slotTableBuilder), currentDataServers);
        logger.info("[selectFollower4BalanceOut] target={}, followerSize={}, candidates={}",
            followerDataServer, followerSlots.size(), candidates);
        for (Integer followerSlot : followerSlots) {
            for (String candidate : candidates) {
                DataNodeSlot candidateDataNodeSlot = slotTableBuilder.getDataNodeSlot(candidate);
                if (candidateDataNodeSlot.containsLeader(followerSlot)) {
                    logger
                        .info(
                            "[selectFollower4BalanceOut] conflict leader, target={}, follower={}, candidate={}",
                            followerDataServer, followerSlot, candidate);
                    continue;
                }
                if (candidateDataNodeSlot.containsFollower(followerSlot)) {
                    logger
                        .info(
                            "[selectFollower4BalanceOut] conflict follower, target={}, follower={}, candidate={}",
                            followerDataServer, followerSlot, candidate);
                    continue;
                }
                return Tuple.of(candidate, followerSlot);
            }
        }
        return null;
    }

    public SlotTableBuilder getSlotTableBuilder() {
        return slotTableBuilder;
    }

    private List<String> getCandidateDataServers(Collection<String> excludes,
                                                 Comparator<String> comp,
                                                 Collection<String> candidateDataServers) {
        Set<String> candidates = Sets.newHashSet(candidateDataServers);
        candidates.removeAll(excludes);
        List<String> ret = Lists.newArrayList(candidates);
        if (comp != null) {
            ret.sort(comp);
        }
        return ret;
    }

}
