package resa.scheduler;

import org.apache.storm.Config;
import org.apache.storm.scheduler.*;
import org.apache.storm.shade.org.apache.curator.framework.CuratorFramework;
import org.apache.storm.shade.org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.storm.shade.org.apache.curator.framework.recipes.cache.NodeCacheListener;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import resa.shedding.tools.DRSzkHandler;
import resa.util.ConfigUtil;

import java.util.*;

/**
 * Created by 44931 on 2018/2/9.
 */
public class AutoScalingResourceOnYarnScheduler implements IScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AutoScalingResourceOnYarnScheduler.class);

    private long timestamp = 0L;
    private static boolean checkResource = true;
    private Cluster _cluster;
    private Topologies _topologies;
    private long waitTime;
    private long startTime;
    protected transient CuratorFramework client;

    private static Set<WorkerSlot> badSlots(Map<WorkerSlot, List<ExecutorDetails>> existingSlots, int numExecutors, int numWorkers) {
        if (numWorkers != 0) {
            Map<Integer, Integer> distribution = Utils.integerDivided(numExecutors, numWorkers);
            Set<WorkerSlot> slots = new HashSet<WorkerSlot>();

            for (Map.Entry<WorkerSlot, List<ExecutorDetails>> entry : existingSlots.entrySet()) {
                Integer executorCount = entry.getValue().size();
                Integer workerCount = distribution.get(executorCount);
                if (workerCount != null && workerCount > 0) {
                    slots.add(entry.getKey());
                    workerCount--;
                    distribution.put(executorCount, workerCount);
                }
            }

            for (WorkerSlot slot : slots) {
                existingSlots.remove(slot);
            }

            return existingSlots.keySet();
        }

        return null;
    }

    public static Set<WorkerSlot> slotsCanReassign(Cluster cluster, Set<WorkerSlot> slots) {
        Set<WorkerSlot> result = new HashSet<WorkerSlot>();
        for (WorkerSlot slot : slots) {
            if (!cluster.isBlackListed(slot.getNodeId())) {
                SupervisorDetails supervisor = cluster.getSupervisorById(slot.getNodeId());
                if (supervisor != null) {
                    Set<Integer> ports = supervisor.getAllPorts();
                    if (ports != null && ports.contains(slot.getPort())) {
                        result.add(slot);
                    }
                }
            }
        }
        return result;
    }

    private static Map<WorkerSlot, List<ExecutorDetails>> getAliveAssignedWorkerSlotExecutors(Cluster cluster, String topologyId) {
        SchedulerAssignment existingAssignment = cluster.getAssignmentById(topologyId);
//        Map<ExecutorDetails, WorkerSlot> executorToSlot = null;
//        if (existingAssignment != null) {
//            executorToSlot = existingAssignment.getExecutorToSlot();
//        }
        //return Utils.reverseMap(executorToSlot);
        Map<WorkerSlot, List<ExecutorDetails>> result = new HashMap<>();
        if (existingAssignment == null) {
            return result;
        }
        Map<WorkerSlot, Collection<ExecutorDetails>> workerSlotCollectionMap = existingAssignment.getSlotToExecutors();
        if (workerSlotCollectionMap != null && !workerSlotCollectionMap.isEmpty()) {
            for (Map.Entry entry : workerSlotCollectionMap.entrySet()) {
                List<ExecutorDetails> tempList = new ArrayList<>();
                Iterator iterator = ((Collection)entry.getValue()).iterator();
                while (iterator.hasNext()) {
                    tempList.add((ExecutorDetails) iterator.next());
                }
                result.put((WorkerSlot) entry.getKey(),tempList);
            }
        }
        return result;
    }

    private void autoScalingResourceSchedule(Topologies topologies, Cluster cluster) {
        _cluster = cluster;
        _topologies = topologies;
        LOG.info("resource state is {} ", checkResource);
        if (checkResource) {
            List<TopologyDetails> needsSchedulingTopologies = cluster.needsSchedulingTopologies(topologies);
            //handle the bad slot and get all need slot.
            int needSlot = 0;
            int assignedWorkerSlotNumber = 0;
            for (TopologyDetails topology : needsSchedulingTopologies) {
                List<WorkerSlot> availableSlotsForHandleBadSlots = cluster.getAvailableSlots();
                Set<ExecutorDetails> allExecutors = (Set<ExecutorDetails>) topology.getExecutors();

                Map<WorkerSlot, List<ExecutorDetails>> aliveAssigned = getAliveAssignedWorkerSlotExecutors(cluster, topology.getId());
                Set<ExecutorDetails> aliveExecutors = new HashSet<ExecutorDetails>();
                for (List<ExecutorDetails> list : aliveAssigned.values()) {
                    aliveExecutors.addAll(list);
                }

                Set<WorkerSlot> canReassignSlots = slotsCanReassign(cluster, aliveAssigned.keySet());
                int totalSlotsToUse = Math.min(topology.getNumWorkers(), canReassignSlots.size() + availableSlotsForHandleBadSlots.size());

                Set<WorkerSlot> badSlots = null;
                if (totalSlotsToUse > aliveAssigned.size() || !allExecutors.equals(aliveExecutors)) {
                    badSlots = badSlots(aliveAssigned, allExecutors.size(), totalSlotsToUse);
                }
                if (badSlots != null) {
                    LOG.info("topology {} have {} badslot", topology.getName(), badSlots.size());
                    cluster.freeSlots(badSlots);
                }
                needSlot += topology.getNumWorkers();
                assignedWorkerSlotNumber += slotsCanReassign(cluster, aliveAssigned.keySet()).size();
            }
            LOG.info("needSlot {} and assignedWorkerSlotNumber {}", needSlot, assignedWorkerSlotNumber);
            // check whether slots are enough.
            List<WorkerSlot> availableSlots = cluster.getAvailableSlots(); // get available slot again.
            int oneSupervisorPortNumber = 0;
            if (!cluster.getSupervisors().isEmpty()) {
                oneSupervisorPortNumber = cluster.getAssignablePorts(cluster.getSupervisors().values().iterator().next()).size();
            }
            int allAvailableSlotNumber = availableSlots.size() + assignedWorkerSlotNumber;
            if (needSlot <= allAvailableSlotNumber
                    && allAvailableSlotNumber - needSlot <= oneSupervisorPortNumber) {
                LOG.info("state 1"+needSlot+":"+allAvailableSlotNumber+":"+oneSupervisorPortNumber);
                doSchedlue(topologies, cluster);
                checkResource = true;
            } else if (needSlot > allAvailableSlotNumber) {
                LOG.info("state 2"+needSlot+":"+allAvailableSlotNumber+":"+oneSupervisorPortNumber);
                requestYarnForADDResource(needSlot, allAvailableSlotNumber, oneSupervisorPortNumber);
                checkResource = false;
            } else if (needSlot !=0 && allAvailableSlotNumber - needSlot > oneSupervisorPortNumber) {
                LOG.info("state 3"+needSlot+":"+allAvailableSlotNumber+":"+oneSupervisorPortNumber);
                requestYarnForSUBResource(needSlot, allAvailableSlotNumber, oneSupervisorPortNumber);
                checkResource = false;
            }
            startTime = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - startTime <= waitTime) {
            LOG.info("waiting for resource!");
        } else {
            LOG.warn("Have no enough resource and wating-timeout!");
            new DefaultScheduler().schedule(topologies,cluster);
            checkResource = true;
        }
    }

    private void doSchedlue(Topologies topologies, Cluster cluster) {
        //Centralize distribution instead of evenly distributed.
        List<TopologyDetails> needsSchedulingTopologies = cluster.needsSchedulingTopologies(topologies);
        for (TopologyDetails topology : needsSchedulingTopologies) {
            boolean needsScheduling = cluster.needsScheduling(topology);
            Map<String, List<ExecutorDetails>> componentToExecutors = cluster.getNeedsSchedulingComponentToExecutors(topology);
            Map<ExecutorDetails,String> executorTocomponent = cluster.getNeedsSchedulingExecutorToComponents(topology);
            LOG.debug("needs scheduling(component->executor): " + componentToExecutors);
            LOG.debug("needs scheduling(executor->components): " + executorTocomponent);
            if (!needsScheduling) {
                LOG.info("Topology {} does not need scheduling.", topology.getName());
            } else if (!componentToExecutors.isEmpty()) {
                LOG.info("Topology {} needs scheduling.", topology.getName());

                SchedulerAssignment currentAssignment = cluster.getAssignmentById(topology.getId());
                if (currentAssignment != null) {
                    LOG.info("current assignments: " + currentAssignment.getExecutorToSlot());
                } else {
                    LOG.info("current assignments: {}");
                }

                // desc sort supervisor by available port.
                Map<String, SupervisorDetails> supervisors = _cluster.getSupervisors();
                List<SupervisorDetails> nodes = new ArrayList<>();
                for (Map.Entry entry : supervisors.entrySet()) {
                    nodes.add((SupervisorDetails) entry.getValue());
                }
                Collections.sort(nodes, new Comparator<SupervisorDetails>() {
                    @Override
                    public int compare(SupervisorDetails o1, SupervisorDetails o2) {
                        return _cluster.getAvailableSlots(o2).size() - _cluster.getAvailableSlots(o1).size();
                    }
                });

                //do schedule.
                Set<ExecutorDetails> tempExecutorDetails = executorTocomponent.keySet();
                List<ExecutorDetails> executors = new LinkedList<>();
                for (ExecutorDetails tempExecutorDetail: tempExecutorDetails) {
                    executors.add(tempExecutorDetail);
                }
                Collection<WorkerSlot> usedSlots = _cluster.getUsedSlotsByTopologyId(topology.getId());
                int usedSlotsSize = 0;
                if (usedSlots != null) {
                    usedSlotsSize = usedSlots.size();
                }
                int tpNeedSlotsNum = topology.getNumWorkers() - usedSlotsSize;
                LinkedList<Integer> numofExecutorToSolt
                        = calcExecutorAssign(executorTocomponent.size(), tpNeedSlotsNum);
                LOG.debug("num of executor to solt:"+numofExecutorToSolt);

                int cursor = 0;
                for (SupervisorDetails node : nodes) {
                    List<WorkerSlot> workerSlots = _cluster.getAvailableSlots(node);
                    for (WorkerSlot workerSlot : workerSlots) {
                        List<ExecutorDetails> assignExecutor = new LinkedList<>();
                        int length = numofExecutorToSolt.pop();
                        int j = cursor;
                        for (; j < cursor + length && j < executors.size(); j++) {
                            assignExecutor.add(executors.get(j));
                        }
                        cursor = j;
                        //Do assignment.
                        cluster.assign(workerSlot, topology.getId(), assignExecutor);
                        if (cursor >= executors.size()) {
                            break;
                        }
                    }
                    if (cursor >= executors.size()) {
                        break;
                    }
                }
            }
        }
    }

    private LinkedList<Integer> calcExecutorAssign(int executorNum, int slotNum) {
        LinkedList<Integer> numofExecutorToSolt = new LinkedList<>();
        int quotient = executorNum / slotNum;
        int remainder = executorNum % slotNum;
        for (int i=0; i<slotNum; i++ ) {
            if (remainder > 0) {
                numofExecutorToSolt.add(quotient+1);
                remainder--;
            } else {
                numofExecutorToSolt.add(quotient);
            }
        }
        return numofExecutorToSolt;
    }


    private void requestYarnForSUBResource(int needSlot, int allAvailableSlotNumber, int oneSupervisorPortNumber) {
        int slot = needSlot - allAvailableSlotNumber; // It is a negative.
        int supervisor = (int) Math.ceil(slot * 1.0 / oneSupervisorPortNumber);
        List<String> nodes = getNeedRemoveNode(supervisor);
        try {
            DRSzkHandler.sentResourceRequest(supervisor);
            DRSzkHandler.sentSUBnodeIDs(nodes);
            LOG.info("sent sub resource request success!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<String> getNeedRemoveNode(int supervisor) {
        Map<String, SupervisorDetails> supervisors = _cluster.getSupervisors();
        List<String> res = new ArrayList<>();
        List<SupervisorDetails> nodes = new ArrayList<>();
        for (Map.Entry entry : supervisors.entrySet()) {
            nodes.add((SupervisorDetails) entry.getValue());
        }
        Collections.sort(nodes, new Comparator<SupervisorDetails>() {
            @Override
            public int compare(SupervisorDetails o1, SupervisorDetails o2) {
                return _cluster.getAvailableSlots(o1).size() - _cluster.getAvailableSlots(o2).size();
            }
        });

        for (int i=0; i<supervisor; i++) {
            res.add(nodes.get(i).getHost());
        }
        return res;
    }

    private void requestYarnForADDResource(int needSlot, int allAvailableSlotNumber, int oneSupervisorPortNumber) {
        int slot = needSlot - allAvailableSlotNumber; // It could NOT be negative
        int supervisor = (int) Math.ceil(slot * 1.0 / oneSupervisorPortNumber);
        try {
            DRSzkHandler.sentResourceRequest(supervisor);
            LOG.info("sent add resource request success!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void watchYarnResponse() throws Exception {
        NodeCache nodeCache = DRSzkHandler.createNodeCache("/resource/response");
        LOG.info(" watch /resource/response"+checkResource);
        nodeCache.getListenable().addListener(new NodeCacheListener() {

            public void nodeChanged() throws Exception {
                int flag = Integer.valueOf(new String(nodeCache.getCurrentData().getData()));
                if (flag == 0) {
                    LOG.warn("have no enough resource! "+checkResource);
                } else if (flag == 1) {
                    checkResource = true;
                    LOG.info("now have enough resource! ");

                }
//                if (!checkResource && _cluster != null && _topologies != null) {
//                    doSchedlue(_topologies, _cluster);
//                    checkResource = true;
//                }
            }
        }, DRSzkHandler.EXECUTOR_SERVICE);
    }

    @Override
    public void prepare(Map conf) {
        waitTime = ConfigUtil.getLong(conf,"request.resource.wait",5000);
        startTime = 0;
        List zkServer = (List) conf.get(Config.STORM_ZOOKEEPER_SERVERS);
        int port = Utils.getInt(conf.get(Config.STORM_ZOOKEEPER_PORT));
        client = DRSzkHandler.newClient(zkServer.get(0).toString(), port, 6000, 6000, 1000, 3);
        if (!DRSzkHandler.clientIsStart()) {
            try {
                DRSzkHandler.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            watchYarnResponse();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void schedule(Topologies topologies, Cluster cluster) {
        autoScalingResourceSchedule(topologies, cluster);
    }

}
