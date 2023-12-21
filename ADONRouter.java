/**
 * 
 */
package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import routing.util.RREPPacket;
import routing.util.RREQPacket;
import routing.util.RERRPacket;

public class ADONRouter extends ActiveRouter {
	private final ReadWriteLock routingTableLock = new ReentrantReadWriteLock();

	private Map<DTNHost, Route> routingTable;
	private Queue<Message> messageQueue;
	private static int lastRreqId = 0;
	private static int lastRerrId = 0;
	private static int lastRrepId = 0;
    private int messageSize = 1024;
    private int maxHops = 255;
    private Set<String> rebroadcastedRREQs = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	public ADONRouter(Settings s) {
		super(s);
	    this.routingTable = new ConcurrentHashMap<>();
	    this.messageQueue = new ConcurrentLinkedQueue<>();
	}
	
    protected ADONRouter(ADONRouter r) {
        super(r);
        this.routingTable = new ConcurrentHashMap<>(r.routingTable);
        this.messageQueue = new ConcurrentLinkedQueue<>(r.messageQueue);
        this.rebroadcastedRREQs = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.rebroadcastedRREQs.addAll(r.rebroadcastedRREQs);
    }
	
    public void printRoutingTable() {
        routingTableLock.readLock().lock();
        try {
            System.out.println("Routing Table for " + this.getHost());
            for (Map.Entry<DTNHost, Route> entry : routingTable.entrySet()) {
                DTNHost host = entry.getKey();
                Route route = entry.getValue();
                System.out.println("Destination: " + host + 
                                   ", Next Hop: " + route.getNextHop() +
                                   ", Hop Count: " + route.getHopCount() +
                                   ", Expiry Time: " + route.getExpiryTime());
            }
        } finally {
            routingTableLock.readLock().unlock();
        }
    }

    
    @Override
    public void update() {
        super.update();
//        printRoutingTable();
        cleanupExpiredRoutes();
        processMessages();
        processControlMessages();
    }
    
    @Override
	public MessageRouter replicate() {
		return new ADONRouter(this);
	}
    
    @Override
    public int receiveMessage(Message message, DTNHost fromHost) {
        if (message instanceof RREQPacket) {
            processRREQ((RREQPacket) message, fromHost);
        } else if (message instanceof RREPPacket) {
            processRREP((RREPPacket) message);
        } else if (message instanceof RERRPacket) {
            processRERR((RERRPacket) message);
        }
        return super.receiveMessage(message, fromHost);
    }

 
    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        DTNHost otherNode = con.getOtherNode(getHost());

        if (con.isUp()) {
            routingTable.put(otherNode, new Route(otherNode, 1, 255));

            if (!this.routingTable.containsKey(otherNode)) {
                initiateRREQ(otherNode);
                
            }
        } else {
            routingTableLock.writeLock().lock();
            try {
                routingTable.remove(otherNode);
                RERRPacket rerr = new RERRPacket(getHost(), otherNode, generateRERRId(), messageSize);
                broadcastRERR(rerr);
            } finally {
                routingTableLock.writeLock().unlock();
            }
        }
    }
    
    @Override
    protected void dropExpiredMessages() {
    	super.dropExpiredMessages();
    }
    
    @Override
    public boolean createNewMessage(Message m) {
        makeRoomForNewMessage(m.getSize());
        boolean isCreated = super.createNewMessage(m);
        if (isCreated && !(m instanceof RREQPacket) && !(m instanceof RREPPacket) && !(m instanceof RERRPacket)) {
            messageQueue.add(m);
//            System.out.println("Message queued: " + m.getId());
        }
        return isCreated;
    }
    
    private boolean hasRoute(DTNHost dest) {
        routingTableLock.readLock().lock();
        try {
            Route route = routingTable.get(dest);
            return (route != null && !route.isExpired());
        } finally {
            routingTableLock.readLock().unlock();
        }
    }
    
    private void cleanupExpiredRoutes() {
        routingTableLock.writeLock().lock();
        try {
            routingTable.keySet().removeIf(host -> routingTable.get(host).isExpired());
        } finally {
            routingTableLock.writeLock().unlock();
        }
    }
    
    private void processControlMessages() {
        Iterator<Message> it = messageQueue.iterator();
        while (it.hasNext()) {
            Message msg = it.next();
            if (msg instanceof RREQPacket) {
                RREQPacket rreq = (RREQPacket) msg;
                processRREQ(rreq, rreq.getFromHost());
                it.remove();
            } else if (msg instanceof RREPPacket) {
                RREPPacket rrep = (RREPPacket) msg;
                processRREP(rrep);
                it.remove(); 
            } else if (msg instanceof RERRPacket) {
                RERRPacket rrep = (RERRPacket) msg;
                processRERR(rrep);
                it.remove();
            }
        }
    }
    
    private void processMessages() {
        List<Message> messageCopy = new ArrayList<>(getMessageCollection());

        for (Message m : messageCopy) {
//            if (m instanceof RREQPacket || m instanceof RREPPacket || m instanceof RERRPacket) {
//                continue;
//            }
            
            if (hasRoute(m.getTo())) {
                sendMessage(m, m.getTo());
            } else {
                initiateRREQ(m.getTo());
            }
        }
    }
    
    private void sendMessage(Message m, DTNHost dest) {
        routingTableLock.readLock().lock();
        try {
            Route route = routingTable.get(dest);
            if (route != null && !route.isExpired()) {
                DTNHost nextHop = route.getNextHop();
//                System.out.println("Routing message: " + m + " to next hop: " + nextHop);
                this.getHost().sendMessage(m.getId(), nextHop);
            } else {
                System.out.println("No valid route for message: " + m);
            }
        } finally {
            routingTableLock.readLock().unlock();
        }
    }
    
    private void initiateRREQ(DTNHost dest) {
        String id = generateRREQId();
        
        RREQPacket rreq = new RREQPacket(this.getHost(), dest, id, messageSize);
        rreq.setTtl(maxHops);
        messageQueue.add(rreq);
        
        for (Connection con : getConnections()) {
            DTNHost otherNode = con.getOtherNode(this.getHost());
//            System.out.println("Broadcasting RREQ " + id + " to " + otherNode);
            otherNode.receiveMessage(rreq, this.getHost());
        }
    }
    
    private void processRREP(RREPPacket rrep) {
        routingTableLock.writeLock().lock();
        try {
            Route newRoute = new Route(rrep.getSource(), rrep.getHopCount(), rrep.getTtl());
            routingTable.put(rrep.getSource(), newRoute);
        } finally {
            routingTableLock.writeLock().unlock();
        }
    }
    
    private void processRREQ(RREQPacket rreq, DTNHost fromHost) {
//    	System.out.println("this " + this.getHost() + " dest " + rreq.getDestination());
//    	System.out.println(this.getHost().toString() == rreq.getDestination().toString());
    	Route newRoute = new Route(rreq.getSource(), rreq.getHopCount(), rreq.getTtl());
    	routingTable.put(rreq.getSource(), newRoute);
        if (this.getHost().equals(rreq.getDestination())) {
            RREPPacket rrep = new RREPPacket(this.getHost(), rreq.getSource(), generateRREPId(), messageSize);
            System.out.println("Sending RREP " + rrep + " to " + rrep.getDestination());
            rrep.setHopCount(0);
            sendRREP(rrep, rreq.getSource());
        } else if (hasRoute(rreq.getDestination())) {
            Route route = routingTable.get(rreq.getDestination());
            rreq.incrementHopCount();
            DTNHost nextHop = route.getNextHop();
            nextHop.receiveMessage(rreq, this.getHost());
        } else {
            broadcastRREQ(rreq, fromHost);
        }
    }
    
    private void broadcastRREQ(RREQPacket rreq, DTNHost fromHost) {
        for (Connection con : getConnections()) {
            DTNHost otherNode = con.getOtherNode(this.getHost());
            if (!otherNode.equals(fromHost)) { 
                otherNode.receiveMessage(rreq.replicate(), this.getHost());
            }
        }
    }

    private void broadcastRERR(RERRPacket rerr) {
    	messageQueue.add(rerr);
        for (Connection con : getConnections()) {
            DTNHost otherNode = con.getOtherNode(this.getHost());
            otherNode.receiveMessage(rerr.replicate(), this.getHost());
        }
    }

    private void processRERR(RERRPacket rerr) {
        routingTableLock.writeLock().lock();
        try {
            this.routingTable.remove(rerr.getUnreachableHost());
        } finally {
            routingTableLock.writeLock().unlock();
        }
    }

    private synchronized String generateRREQId() {
        lastRreqId++;
        return "RREQ_" + this.getHost() + "_" + lastRreqId;
    }
    
    private synchronized String generateRERRId() {
        lastRerrId++;
        return "RERR_" + this.getHost() + "_" + lastRerrId;
    }
    
    private synchronized String generateRREPId() {
        lastRrepId++;
        return "RREP_" + this.getHost() + "_" + lastRrepId;
    }
    
    private void sendRREP(RREPPacket rrep, DTNHost destination) {
        Route route = routingTable.get(destination);
        if (route != null && !route.isExpired()) {
            DTNHost nextHop = route.getNextHop();
            nextHop.receiveMessage(rrep, this.getHost());
        }
        messageQueue.add(rrep); 
    }
 }
