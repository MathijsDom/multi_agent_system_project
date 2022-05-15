package agent;

import java.lang.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.logging.Logger;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import agent.behavior.Behavior;
import agent.behavior.BehaviorState;
import environment.*;
import environment.world.agent.Agent;
import environment.world.agent.AgentRep;
import environment.world.destination.DestinationRep;
import environment.world.generator.PacketGenerator;
import environment.world.packet.Packet;
import environment.world.packet.PacketRep;
import environment.world.wall.SolidWallRep;
import environment.world.wall.WallRep;
import support.ActionOutcome;
import support.CommunicationOutcome;
import support.InfNOP;
import support.InfPickCrumb;
import support.InfPickGeneratorPacket;
import support.InfPickPacket;
import support.InfPutCrumb;
import support.InfPutDirPheromone;
import support.InfPutFlag;
import support.InfPutPacket;
import support.InfPutPheromone;
import support.InfRemovePheromone;
import support.InfSkip;
import support.InfStealPacket;
import support.InfStep;
import support.Influence;
import support.Outcome;
import support.PerceptionOutcome;
import util.MyColor;
import util.event.AgentActionEvent;
import util.event.AgentHandledEvent;
import util.event.BehaviorChangeEvent;
import util.event.EnergyUpdateEvent;

/**
 * This class represents the implementation of an Agent in the MAS. It
 * interacts with the Environment for new information of the world by running
 * a separate thread. The agent implementation contains the local view on the
 * world and any received messages. It also has a behavior.
 */
abstract public class AgentImp extends ActiveImp implements AgentState, AgentCommunication, AgentAction {


    private BehaviorState lnkBehaviorState;

    private final List<Mail> messages;

    private final MailBuffer outgoingMails;

    private final EventBus eventBus;
    
    private boolean committedAction;


    /**
     * The memory of an agent has the form of a key mapped to a memory fragment (represented as String)
     * e.g.  "target" -> "3, 4"
     */
    private Map<String, String> memory;
    private static final int MAX_MEMORY_FRAGMENTS = 10;

    private final Logger logger = Logger.getLogger(AgentImp.class.getName());



    /**
     * Initialize a new instance of AgentImp with id <ID>. Every new AgentImp
     * instance is initialized with an empty buffer for incoming messages
     * and an empty buffer for outgoing mails.
     * @param ID The id of this AgentImp instance.
     * @post new.getName()==name
     * @post new.getID()==ID
     * @post new.getMailBuffer() <> null
     */
    public AgentImp(ActiveItemID ID, EventBus eventBus) {
        super(ID);
        this.eventBus = eventBus;
        //this.name = name;
        this.messages = new ArrayList<>();
        //synchronize=false;
        this.outgoingMails = new MailBuffer();
        memory = new HashMap<>();


        this.committedAction = false;

        this.eventBus.register(this);
    }



    // ===============================
    // | Agent Communication Methods |
    // ===============================

    /**
     * Create a mail from this AgentImp to <receiver> and with message <message> and add the resulting mail to the buffer of outgoing
     * mails.
     * @param receiver The representation of the agent to write the message to
     * @param message The message to send
     */
    @Override
    public final void sendMessage(AgentRep receiver, String message) {
        this.sendMessage(receiver.getName(), message);
    }


    /**
     * Create a mail from this AgentImp to <to> and with message <message> and add the resulting mail to the buffer of outgoing
     * mails.
     * @param to The name of the agent to write the message to
     * @param message The message to send
     */
    private void sendMessage(String to, String message) {
        this.logger.fine(String.format("agentImp %d buffers a mail", getActiveItemID().getID()));

        Mail mail = new Mail(getName(), to, message);
        this.getMailBuffer().addMail(mail);
    }

    /**
     * Broadcast a message to all other agents.
     * @param message The message to transmit.
     */
    @Override
    public void broadcastMessage(String message) {
        this.getEnvironment().getAgentWorld().getAgents().stream()
                .filter(a -> a != this.getAgent())
                .forEach(a -> this.sendMessage(a.getName(), message));
    }


    /**
     * Gets message at the given index from the message queue.
     * @param index  The index of the desired message.
     */
    @Override
    public Mail getMessage(int index) {
        return messages.get(index);
    }

    /**
     * Get the number of messages in the incoming message queue.
     */
    @Override
    public int getNbMessages() {
        return messages.size();
    }

    /**
     * Removes the message at the specified index from the incoming message queue.
     * @param index  The index of the message to remove.
     */
    @Override
    public void removeMessage(int index) {
        messages.remove(index);
    }


    /**
     * Retrieve all the incoming messages.
     * @return A collection with the received messages from other agents.
     */
    @Override
    public Collection<Mail> getMessages() {
        return messages;
    }

    /**
     * Clear the incoming message queue.
     */
    @Override
    public void clearMessages() {
        messages.clear();
    }

    public Set<Coordinate> getPriorityCoordinates(){
        return new HashSet<Coordinate>();
    }


    // ========================
    // | Agent Action Methods |
    // ========================

    /**
     * Do nothing, skip this turn.
     */
    @Override
    public final void skip() {
        this.logger.fine(String.format("agent %d proposes to do a skip.", this.getActiveItemID().getID()));

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
            this.generateActionOutcome(new InfSkip(getEnvironment(), getActiveItemID())));
    }

    /**
     * Take a step to the give coordinate.
     *
     * @param x  The x coordinate of the area to step to.
     * @param y  The y coordinate of the area to step to.
     */
    @Override
    public final void step(int x, int y) {
        this.logger.fine(String.format("agent %d proposes to do a step.", getActiveItemID().getID()));

        this.concludeWithCondition(this.hasSufficientEnergy(this.hasCarry() ? EnergyValues.BATTERY_DECAY_STEP_WITH_CARRY : EnergyValues.BATTERY_DECAY_STEP),
            this.generateActionOutcome(new InfStep(getEnvironment(), x, y, getActiveItemID())));
    }

    /**
     * Put a packet down on the given Coordinate. If the cell contains
     * a destination, the packet will be delivered. If the cell is empty,
     * the packet will be put down.
     *
     * @param x  The x coordinate of the target area.
     * @param y  The y coordinate of the target area.
     */
    @Override
    public final void putPacket(int x, int y) {
        this.logger.fine(String.format("agent %d proposes to do put a packet.", getActiveItemID().getID()));

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
            this.generateActionOutcome(new InfPutPacket(getEnvironment(), x, y, getActiveItemID())));
    }

    /**
     * Pick up a packet from the given Coordinate.
     *
     * @param x  The x coordinate of the area of the packet.
     * @param y  The y coordinate of the area of the packet.
     * @throws RuntimeException  if no packet is present on the specified coordinate.
     */
    @Override
    public final void pickPacket(int x, int y) {
        this.logger.fine(String.format("agent %d proposes to pick a packet.", getActiveItemID().getID()));

        var packet = getEnvironment().getPacketWorld().getItem(x, y);
        var generator = getEnvironment().getPacketGeneratorWorld().getItem(x, y);

        if (packet != null) {
            this.pickRegularPacket(packet);
        } else if (generator != null && generator.getAmtPacketsInBuffer() > 0) {
            this.pickGeneratorPacket(generator);
        } else {
            throw new RuntimeException(String.format("No packet at location (%d,%d).", x, y));
        }
    }

    /**
     * Steal a packet from another agent at the given coordinate.
     *
     * @param x  The x coordinate of the carrier of the packet.
     * @param y  The y coordinate of the carrier of the packet.
     * @throws RuntimeException  if no packet is present on the specified coordinate.
     */
    @Override
    public final void stealPacket(int x, int y) {
        this.logger.fine(String.format("agent %d proposes to steal a packet.", getActiveItemID().getID()));

        var agent = getEnvironment().getAgentWorld().getItem(x, y);

        if (agent == null) {
            throw new RuntimeException(String.format("No agent at location (%d,%d) to steal from.", x, y));
        }

        var packet = agent.getCarry().orElseThrow(() -> new RuntimeException(String.format("No packet at location (%d,%d).", x, y)));

        var agentColor = getAgent().getColor();
        var packetColor = packet.getColor();

        if (agentColor.isPresent() && agentColor.get() != packetColor) {
            throw new RuntimeException(String.format("Agent %d cannot pick packet with color %s (agent restricted to color %s).",
                    getActiveItemID().getID(), MyColor.getName(packetColor), MyColor.getName(agentColor.get())));
        }

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
                this.generateActionOutcome(new InfStealPacket(getEnvironment(), packet.getX(), packet.getY(), getActiveItemID())));
    }

    /**
     * Put a pheromone in the environment at the given coordinate and lifetime.
     *
     * @param x         The x coordinate of the target area.
     * @param y         The y coordinate of the target area.
     * @param lifetime  The lifetime for the pheromone (maximum lifetime can be found at {@link environment.world.pheromone.Pheromone#MAX_LIFETIME}).
     */
    @Override
    public final void putPheromone(int x, int y, int lifetime) {
        this.logger.fine(String.format("agent %d proposes to put a pheromone.", getActiveItemID().getID()));

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
            this.generateActionOutcome(new InfPutPheromone(getEnvironment(), x, y, getActiveItemID(), lifetime)));
    }

    /**
     * Put a directed pheromone in the environment at the given coordinate, lifetime and target cell.
     *
     * @param x         The x coordinate of the target area.
     * @param y         The y coordinate of the target area.
     * @param lifetime  The lifetime for the pheromone.
     * @param target    The area the directed pheromone has to point to.
     */
    @Override
    public final void putDirectedPheromone(int x, int y, int lifetime, CellPerception target) {
        this.logger.fine(String.format("agent %d proposes to put a directed pheromone.", getActiveItemID().getID()));

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
            this.generateActionOutcome(new InfPutDirPheromone(getEnvironment(), x, y, getActiveItemID(), lifetime, target)));
    }

    /**
     * Remove a pheromone from the environment at the given coordinate.
     *
     * @param x  The x coordinate of the target area.
     * @param y  The y coordinate of the target area.
     */
    @Override
    public final void removePheromone(int x, int y) {
        this.logger.fine(String.format("agent %d proposes to put a pheromone.", getActiveItemID().getID()));

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
            this.generateActionOutcome(new InfRemovePheromone(getEnvironment(), x, y, getActiveItemID())));
    }

    /**
     * Put a flag with the specified Color in the environment on the given coordinate.
     *
     * @param x      The x coordinate of the target area.
     * @param y      The y coordinate of the target area.
     * @param color  The color of the new flag.
     */
    @Override
    public final void putFlag(int x, int y, Color color) {
        this.logger.fine(String.format("agent %d proposes to put a flag.", getActiveItemID().getID()));

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
            this.generateActionOutcome(new InfPutFlag(getEnvironment(), x, y, getActiveItemID(), color)));
    }

    /**
     * Put a colorless flag in the environment at the given coordinate.
     *
     * @param x  the x coordinate of the target area.
     * @param y  the y coordinate of the target area.
     */
    @Override
    public final void putFlag(int x, int y) {
        this.putFlag(x, y, Color.BLACK);
    }

    /**
     * Put a specified number of crumbs in the environment at the given coordinate.
     *
     * @param x       The x coordinate of the target area.
     * @param y       The y coordinate of the target area.
     * @param number  The number of crumbs to put on target area.
     */
    @Override
    public final void putCrumb(int x, int y, int number) {
        this.logger.fine(String.format("agent %d proposes to put a crumb.", getActiveItemID().getID()));

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
            this.generateActionOutcome(new InfPutCrumb(getEnvironment(), x, y, getActiveItemID(), number)));
    }

    /**
     * Pick up a specified number of crumbs in the environment from the given coordinate. 
     *
     * @param x       The x coordinate of the target area.
     * @param y       The y coordinate of the target area.
     * @param number  The number of crumbs to get from target area.
     */
    @Override
    public final void pickCrumb(int x, int y, int number) {
        this.logger.fine(String.format("agent %d proposes to put a crumb.", getActiveItemID().getID()));

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
            this.generateActionOutcome(new InfPickCrumb(getEnvironment(), x, y, getActiveItemID(), number)));
    }




    private void pickRegularPacket(Packet packet) {
        var agentColor = getAgent().getColor();
        var packetColor = packet.getColor();

        if (agentColor.isPresent() && agentColor.get() != packetColor) {
            throw new RuntimeException(String.format("Agent %d cannot pick packet with color %s (agent restricted to color %s).",
                    getActiveItemID().getID(), MyColor.getName(packetColor), MyColor.getName(agentColor.get())));
        }

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
            this.generateActionOutcome(new InfPickPacket(getEnvironment(), packet.getX(), packet.getY(), getActiveItemID(), packetColor)));
    }

    private void pickGeneratorPacket(PacketGenerator generator) {
        var agentColor = getAgent().getColor();
        var generatorColor = generator.getColor();

        if (agentColor.isPresent() && agentColor.get() != generatorColor) {
            throw new RuntimeException(String.format("Agent %d cannot pick packet with color %s (agent restricted to color %s).",
                getActiveItemID().getID(), MyColor.getName(generatorColor), MyColor.getName(agentColor.get())));
        }

        this.concludeWithCondition(this.hasSufficientEnergyDefault(),
            this.generateActionOutcome(new InfPickGeneratorPacket(getEnvironment(), generator.getX(), generator.getY(), getActiveItemID(), generatorColor)));
    }



    private ActionOutcome generateActionOutcome(Influence influence) {
        return new ActionOutcome(getActiveItemID(), true, getSyncSet(), influence);
    }

    private boolean hasSufficientEnergyDefault() {
        // Default actions such as putting crumbs, skipping, placing flags, ... all require a base amount of energy
        return this.hasSufficientEnergy(EnergyValues.BATTERY_DECAY_SKIP);
    }

    private boolean hasSufficientEnergy(int required) {
        return this.getAgent().getBatteryState() >= required;
    }


    private void concludeWithCondition(boolean condition, Outcome onSuccess) {
        var onFail = this.generateActionOutcome(getAgent().getBatteryState() > 0 ?
                new InfSkip(getEnvironment(), getActiveItemID()) : new InfNOP(this.getEnvironment(), this.getActiveItemID()));

        this.concludePhaseWith(condition ? onSuccess : onFail);
        this.committedAction = true;
    }




    // ============================
    // | Agent Perception Methods |
    // ============================



    /**
     * Returns the optional packet this agent is carrying.
     * @return An optional of the packet the agent carries, or an empty optional otherwise.
     */
    @Override
    public Optional<Packet> getCarry() {
        return this.getAgent().getCarry();
    }
    

    /**
     * Check if the agent is carrying something.
     * @return {@code true} if the agent carries a packet, {@code false} otherwise.
     */
    @Override
    public boolean hasCarry() {
        return this.getAgent().hasCarry();
    }


    /**
     * Get the X coordinate of this agent.
     */
    @Override
    public int getX() {
        return this.getAgent().getX();
    }

    /**
     * Get the Y coordinate of this agent.
     */
    @Override
    public int getY() {
        return this.getAgent().getY();
    }

    /**
     * Get the coordinates of this agent.
     */
    @Override
    public Coordinate getCoordinates() { return new Coordinate(this.getX(), this.getY()); }


    /**
     * Get the name of this agent.
     */
    @Override
    public String getName() {
        return this.getAgent().getName();
    }


    /**
     * Get the optional color of this agent itself.
     */
    @Override
    public Optional<Color> getColor() {
        return this.getAgent().getColor();
    }


    /**
     * Get the battery state of the agent.
     * @return  The battery state of the agent (from {@link environment.EnergyValues#BATTERY_MIN} to {@link environment.EnergyValues#BATTERY_MAX}).
     */
    @Override
    public int getBatteryState() {
        return this.getAgent().getBatteryState();
    }


    /**
     * Get the current Behavior.
     */
    @Override
    public Behavior getCurrentBehavior() {
        return lnkBehaviorState.getBehavior();
    }
    

    


    /**
     * Get the perception of this agent.
     */
    @Override
    public Perception getPerception() {
        return super.getPerception();
    }


    /**
     * Returns a CellPerception of the previous area this agent stood on.
     */
    @Override
    public CellPerception getPerceptionLastCell() {
        int lastX = this.getAgent().getLastX();
        int lastY = this.getAgent().getLastY();
        
        return this.getPerception().getCellPerceptionOnAbsPos(lastX, lastY);
    }


    /**
     * Check to see if an agent can see any destination.
     *
     * @return {@code true} if this agent sees such a destination, {@code false} otherwise.
     */
    @Override
    public boolean seesDestination() {
        return this.seesDestination(null);
    }

    /**
     * Check to see if an agent can see a destination with the specified color.
     *
     * @return {@code true} if this agent sees such a destination, {@code false} otherwise.
     */
    @Override
    public boolean seesDestination(Color color) {
        int vw = getPerception().getWidth();
        int vh = getPerception().getHeight();
        for (int i = 0; i < vw; i++) {
            for (int j = 0; j < vh; j++) {
                var per = this.getPerception().getCellAt(i, j);
                if (per != null) {
                    DestinationRep destRep = per.getRepOfType(DestinationRep.class);
                    if (destRep != null && Optional.ofNullable(color).map(c -> destRep.getColor().equals(c)).orElse(true)) {
                        return true;
                    }
                }

            }
        }
        return false;
    }


    /**
     * Find a neighbouring cell that contains a destination of a specific color
     *
     * @param color     The color of the destination we are looking for
     *
     * @return          A cell that contains the matching destination,
     *                  null if no neighbouring cell contains a matching destination
     */
    @Override
    public CellPerception getNeighbouringCellWithDestination(Color color){
        var perception = this.getPerception();
        CellPerception[] neighbouringCells = perception.getNeighbours();

        for (var cell: neighbouringCells){
            if (cell != null && cell.containsDestination(color))
                return cell;
        }
        return null;
    }

    /**
     * Find cells in the agent's perception area that contain a destination of a specific color
     *
     * @param color     The color of the destination we are looking for
     *
     * @return          A set of cells that contain the matching destinations,
     *                  An empty set if no perceivable cell contains a matching destination
     */
    @Override
    public Set<CellPerception> getPerceivableCellsWithDestination(Color color){

        var perception = this.getPerception();
        int vw = perception.getWidth();
        int vh = perception.getHeight();
        Set<CellPerception> neighbouringCellsWithDestination = new HashSet<>();

        for (int i = 0; i < vw; i++) {
            for (int j = 0; j < vh; j++) {
                // Look at each cell in the perception area
                var cell = perception.getCellAt(i, j);
                if (cell != null && cell.containsDestination(color)) {
                    neighbouringCellsWithDestination.add(cell);
                }
            }
        }

        return neighbouringCellsWithDestination;
    }



    /**
     * Check to see if this agent can see any packet.
     *
     * @return {@code true} if this agent can see such a packet, {@code false} otherwise.
     */
    @Override
    public boolean seesPacket() {
        return this.seesPacket(null);
    }


    /**
     * Check to see if this agent can see a packet with the specified color.
     *
     * @return {@code true} if this agent can see such a packet, {@code false} otherwise.
     */
    @Override
    public boolean seesPacket(Color color) {
        int vw = getPerception().getWidth();
        int vh = getPerception().getHeight();
        for (int i = 0; i < vw; i++) {
            for (int j = 0; j < vh; j++) {
                var per = this.getPerception().getCellAt(i, j);
                if (per != null) {
                    PacketRep p = per.getRepOfType(PacketRep.class);
                    if (p != null && Optional.ofNullable(color).map(c -> p.getColor().equals(c)).orElse(true)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    /**
     * Find a neighbouring cell that contains a packet
     *
     * @return          A cell that contains a packet,
     *                  null if no neighbouring cell contains a packet
     */
    @Override
    public CellPerception getNeighbouringCellWithPacket(){
        var perception = this.getPerception();
        CellPerception[] neighbouringCells = perception.getNeighbours();
//        Set<CellPerception> neighbouringCellsWithPacket = new HashSet<>();
        for (var cell: neighbouringCells) {
            if (cell != null && cell.containsPacket())
                return cell;
        }
        return null;
    }

    /**
     * Find the set of neighbouring cells that contain a packet
     *
     * @return          A set of neighbouring cells that contain a packet,
     *                  null if no neighbouring cell contains a packet
     */
    @Override
    public Set<CellPerception> getNeighbouringCellsWithPacket(){
        var perception = this.getPerception();
        CellPerception[] neighbouringCells = perception.getNeighbours();
        Set<CellPerception> neighbouringCellsWithPacket = new HashSet<>();
        for (var cell: neighbouringCells) {
            if (cell != null && cell.containsPacket())
                neighbouringCellsWithPacket.add(cell);
        }
        return neighbouringCellsWithPacket;
    }


    /**
     * Find cells in the agent's perception area that contain a packet
     *
     * @return          A set of cells that contain a packet,
     *                  An empty set if no neighbouring cell contains a packet
     */
    @Override
    public Set<CellPerception> getPerceivableCellsWithPacket(){
        var perception = this.getPerception();
        int vw = perception.getWidth();
        int vh = perception.getHeight();
        Set<CellPerception> neighbouringCellsWithPacket = new HashSet<>();

        for (int i = 0; i < vw; i++) {
            for (int j = 0; j < vh; j++) {
                // Look at each cell in the perception area
                var cell = perception.getCellAt(i, j);
                if (cell != null && cell.containsPacket()) {
                    neighbouringCellsWithPacket.add(cell);
                }
            }
        }
        return neighbouringCellsWithPacket;
    }



    /**
     * Check if the agent can walk at some specific coordinates
     *
     * @param x     x-coordinate
     * @param y     y-coordinate
     *
     * @return {@code true} if this agent can walk at these coordinates, {@code false} otherwise.
     */
    @Override
    public boolean canWalk(int x, int y){
        CellPerception cell = this.getPerception().getCellPerceptionOnAbsPos(x, y);
        // If the area is null, it is outside the bounds of the environment
        //  (when the agent is at any edge for example some moves are not possible)
        return cell != null && cell.isWalkable();
    }


    /**
     * Print the world as known by the agent (from his memory).
     *
     * Example:
     * World seen from Bob's memory:
     * -----------------------------------------
     * | U | U | U |P_R|P_R| U | U | U | U | U |
     * -----------------------------------------
     * | U | U | U | U |P_R| U | U | U |P_R| U |
     * -----------------------------------------
     * | U | U | U | U | U | U | U | U |P_R| U |
     * -----------------------------------------
     * | U |P_R|P_R| U | U | U | U | U | U | U |
     * -----------------------------------------
     * | U | U |P_R| U | U | U | U | U | U | U |
     * -----------------------------------------
     * |P_R| U | U | U | U | U | U | U | U | U |
     * -----------------------------------------
     * | U | U | U | U | U | U | U | U | U |D_R|
     * -----------------------------------------
     * | U | U | U | U | U | U | U | U | U | U |
     * -----------------------------------------
     * | U | U | U | U | U | U | U | A | U | U |
     * -----------------------------------------
     * | U | U | U | U |P_R|P_R| U | U | U | U |
     * -----------------------------------------
     * | U | U | U | U |P_R|P_R| U | U |P_R|P_R|
     * -----------------------------------------
     *
     * U = Unknown
     * P_R = Packet_Red
     * D_R = Destination_Red
     * A = this Agent
     *
     * Note: We never print the other agents because they move around and we can not be sure of their location
     */
    @Override
    public void prettyPrintWorld(){
        if (this.getMemoryFragmentKeysContaining("Rep").size() == 0)
            return;

        int minXCoordinate = Integer.MAX_VALUE;
        int maxXCoordinate = 0;
        int minYCoordinate = Integer.MAX_VALUE;
        int maxYCoordinate = 0;

        Map<Coordinate, String> coordinates2Rep = new HashMap<>();
        for (var key: this.getMemoryFragmentKeys()){
            if (key.contains("Rep")) {
                String dataFragment = this.getMemoryFragment(key);
                String[] representationAndColor = AgentState.memoryKey2Rep(key);
                String representation = representationAndColor[0];
                String representationInitials = Representation.rep2Initials(representation);
                String color = null;
                if (representationAndColor.length == 2) {
                    color = representationAndColor[1];
                    representationInitials += "_" + Representation.rep2Initials(color);
                }

                List<Coordinate> coordinatesList = Coordinate.string2Coordinates(dataFragment);
                for (var coordinates : coordinatesList) {
                    coordinates2Rep.put(coordinates, representationInitials);
                }

                for (var coordinates: coordinatesList){
                    int x = coordinates.getX();
                    int y = coordinates.getY();
                    if (x < minXCoordinate)
                        minXCoordinate = x;
                    if (y < minYCoordinate)
                        minYCoordinate = y;
                    if (x > maxXCoordinate)
                        maxXCoordinate = x;
                    if (y > maxYCoordinate)
                        maxYCoordinate = y;
                }
            }
        }

        coordinates2Rep.put(new Coordinate(this.getX(), this.getY()), "A"); // Add this agent's position

        StringBuilder prettyWorld = new StringBuilder(String.format("World seen from %s's memory:\n", this.getName()));


        int cellLength = 3;
        for (int y = minYCoordinate; y <= maxYCoordinate; y++){

            prettyWorld.append("----".repeat(maxXCoordinate - minXCoordinate + 1)).append("-\n");
            for (int x = minXCoordinate; x <= maxXCoordinate; x++){
                prettyWorld.append("|");
                String repInitials = coordinates2Rep.get(new Coordinate(x, y));
                if (repInitials == null){
                    repInitials = "U";
                }
                int textLength = repInitials.length();
                int rightPadding = (cellLength - textLength)/2;
                int leftPadding = cellLength - textLength - rightPadding;
                if (rightPadding > 0)
                    prettyWorld.append(" ".repeat(rightPadding));
                prettyWorld.append(repInitials);
                if (leftPadding > 0)
                    prettyWorld.append(" ".repeat(leftPadding));
            }
            prettyWorld.append("|\n");
        }
        prettyWorld.append("----".repeat(maxXCoordinate - minXCoordinate + 1)).append("-\n");
        System.out.println(prettyWorld);
    }


    /**
     * Adds a memory fragment to this agent (if its memory is not full).
     *
     * @param key     The key associated with the memory fragment
     * @param data    The memory fragment itself
     */
    @Override
    public void addMemoryFragment(String key, String data) {
        if (key != null && !key.equals("") && data != null
                && !data.equals("")) { // && getNbMemoryFragments() < getMaxNbMemoryFragments()
            memory.put(key, data);
        }
    }

    /**
     * Append at the beginning of a memory fragment
     * if the memory fragment corresponding to a given key already exists.
     * Otherwise, adds a memory fragment to this agent (if its memory is not full).
     *
     * @param key     The key associated with the memory fragment.
     * @param data    The data to append to the memory fragment.
     */
    @Override
    public void append2Memory(String key, String data) {
        if (data == null || data.equals("")){
            return;
        }

        String oldData = this.getMemoryFragment(key);
        if (oldData != null){
            this.removeMemoryFragment(key);
            this.addMemoryFragment(key, data + oldData);
        }else{
            this.addMemoryFragment(key, data);
        }
    }

    /**
     * Generate a key for the representation present on a given cell (used to standardize memory fragment storage).
     *
     * @param cell      The cell from which the representation key must be generated.
     *
     * @return String   The representation key generated.
     */
    @Override
    public String rep2MemoryKey(CellPerception cell){
        String key = null;
        // For now, we only allow 3 representations to be memorized: DestinationRep, PacketRep, WallRep
        if (cell.containsAnyDestination()) {
            DestinationRep destinationRep = cell.getRepOfType(DestinationRep.class);
            key = DestinationRep.class + "_" + MyColor.getName(destinationRep.getColor());
        }else if (cell.containsPacket()){
            PacketRep packetRep = cell.getRepOfType(PacketRep.class);
            key = PacketRep.class + "_" + MyColor.getName(packetRep.getColor());
        }else if (cell.containsWall()) {
            WallRep wallRep = cell.getRepOfType(WallRep.class);
            key = WallRep.class.toString();
        }else {
            key = "EmptyRep";
        }

        return key;
    }


    /**
     * Adds the representation on a given cell to this agent's memory (if its memory is not full).
     *
     * @param cell    The cell from which the representation must be memorized.
     */
    @Override
    public void addRep2Memory(CellPerception cell) {
        if (cell == null)
            return;

        String key = rep2MemoryKey(cell);
        if (key == null)
            return;

        Coordinate cellCoordinates = new Coordinate(cell.getX(), cell.getY());
        String data;
        // If a representation with the same key has already been saved in memory
        if (this.getMemoryFragmentKeys().contains(key)) {
            data = this.getMemoryFragment(key); // get the string stored at this key
            // Look through all coordinates stored at this key
            for (Coordinate coordinate : Coordinate.string2Coordinates(data)) {
                // If the coordinate we want to add are already present,
                // we don't need to add it again
                if (coordinate.equals(cellCoordinates)) {
                    return;
                }
            }
        }

        this.append2Memory(key, cellCoordinates.toString());
    }


    /**
     * Memorize all representations that the agent sees in his perception area.
     */
    @Override
    public void memorizeAllPerceivableRepresentations() {
        var perception = this.getPerception();

        int vw = perception.getWidth();
        int vh = perception.getHeight();

        for (int i = 0; i < vw; i++) {
            for (int j = 0; j < vh; j++) {
                // Look at each cell in the perception area
                var cell = perception.getCellAt(i, j);
                if (cell != null) {
                    this.addRep2Memory(cell);
                }
            }
        }
    }

    /**
     * Forget all representations that are not present anymore in the agent's perception area
     * (because a packet was picked up by another agent for example)
     *
     * Note: for now, only remove packets from memory as it is the only movable representation
     */
    @Override
    public void forgetAllUnperceivableRepresentations(){
        var perception = this.getPerception();

        int vw = perception.getWidth();
        int vh = perception.getHeight();

        for (int i = 0; i < vw; i++) {
            for (int j = 0; j < vh; j++) {
                // Look at each cell in the perception area
                var cell = perception.getCellAt(i, j);

                // If the cell exists and does not contain any packet (anymore)
                if (cell != null && !cell.containsPacket()) {
                    Coordinate cellCoordinates = new Coordinate(cell.getX(), cell.getY());
                    // Remove the potential representation of the packet that the agent has in memory
                    this.removeFromMemory(cellCoordinates, PacketRep.class.toString());
                }
            }
        }
    }


    /**
     * Removes a memory fragment with given key from this agent's memory.
     * @param key  The key of the memory fragment to remove.
     */
    @Override
    public void removeMemoryFragment(String key) {
        memory.remove(key);
    }


    /**
     * Removes a specific representation on a given cell from this agent's memory.
     *
     * @param cell    The cell from which the representation must be removed.
     */
    @Override
    public void removeFromMemory(CellPerception cell) {
        Coordinate cellCoordinates = new Coordinate(cell.getX(), cell.getY());
        String representationKey = rep2MemoryKey(cell);
        this.removeFromMemory(cellCoordinates, representationKey);
    }

    /**
     * Removes specific coordinates stored at a subkey from this agent's memory.
     *
     * @param cellCoordinates    The cell coordinates from which the representation must be removed.
     * @param subkey             The substring of a key to remove from memory.
     */
    @Override
    public void removeFromMemory(Coordinate cellCoordinates, String subkey) {
        for(String key: this.getMemoryFragmentKeysContaining(subkey)){
            this.removeCoordinatesFromMemoryByKey(cellCoordinates, key);
        }
    }


    /**
     * Removes the coordinates stored at some key in this agent's memory.
     *
     * @param cellCoordinates    The cell coordinates from which the representation must be removed.
     * @param key                The key at which is currently stored the coordinates to remove.
     */
    private void removeCoordinatesFromMemoryByKey(Coordinate cellCoordinates, String key){
        String newData = "";
        // If a representation of the same key has already been saved in memory
        if (this.getMemoryFragmentKeys().contains(key)) {
            String data = this.getMemoryFragment(key); // Get the string stored at this key
            // Look through all coordinates stored at this key
            for (Coordinate coordinates : Coordinate.string2Coordinates(data)){
                // We only copy as a string the coordinates that should not be removed
                if (! coordinates.equals(cellCoordinates)){
                    newData += coordinates.toString();
                }
            }
            this.removeMemoryFragment(key); // Remove the memory fragment we have to modified
        }

        // If there is still some data to save at the given key after removing the given coordinates
        if (!newData.equals("")) {
            // Save the destination coordinates to memory
            this.addMemoryFragment(key, newData);
        }
    }


    /**
     * Get a memory fragment with given key from this agent's memory.
     * @param key  The key of the memory fragment to retrieve.
     */
    @Override
    public String getMemoryFragment(String key) {
        return this.memory.get(key);
    }

    /**
     * Get all the keys of stored memory fragments in this agent's memory.
     */
    @Override
    public Set<String> getMemoryFragmentKeys() {
        return this.memory.keySet();
    }

    /**
     * Get all the keys of stored memory fragments containing a given substring in this agent's memory.
     *
     * @param subkey    The substring of the memory fragment keys that we are looking for
     *
     * @return          A set of memory fragment keys matching the subkey
     */
    @Override
    public Set<String> getMemoryFragmentKeysContaining(String subkey) {
        Set<String> matchingKeys = new HashSet<>();
        // For all keys in memory
        for (String key : this.getMemoryFragmentKeys()) {
            // If the subkey matches the current key, we save it
            if (key.contains(subkey)) {
                matchingKeys.add(key);
            }
        }
        return matchingKeys;
    }

    /**
     * Retrieve the representations and potential colors from all memory fragments that store them
     * and create cell perception instances out of them.
     *
     * @return          A set of cell perceptions build out of their fragments stored in memory
     */
    public Set<CellPerception> memory2Cells(){
        Set<CellPerception> cells = new HashSet<>();
        for(String key: this.getMemoryFragmentKeysContaining("Rep")){
            cells.addAll(this.memoryKey2Cells(key));
        }
        return cells;
    }

    public Set<CellPerception> memory2CellsWithoutPackets(){
        Set<CellPerception> cells = new HashSet<>();
        for (String key: this.getMemoryFragmentKeysContaining("Rep")){
            if (!key.contains(PacketRep.class.toString()))
                cells.addAll(this.memoryKey2Cells(key));
        }
        return cells;
    }

    /**
     * Retrieve the representations and potential colors from a given memory key and create cell perception instances
     * out of them.
     *
     * @param key       The memory key with which a representation is stored
     *
     * @return          A set of cell perceptions build out from their fragments stored in memory
     */
    public Set<CellPerception> memoryKey2Cells(String key){
        String memoryFragment = this.getMemoryFragment(key);
        List<Coordinate> coordinatesList = Coordinate.string2Coordinates(memoryFragment);

        String[] representationAndColor = AgentState.memoryKey2Rep(key);
        String representation = representationAndColor[0];
        Color color = null;
        if (representationAndColor.length > 1)
            color = Color.getColor(representationAndColor[1]);

        Set<CellPerception> cells = new HashSet<>();

        for (Coordinate coordinates: coordinatesList) {
            CellPerception cell = new CellPerception(coordinates);
            if (representation.equals(PacketRep.class.toString()))
                cell.addRep(new PacketRep(coordinates, color));
            else if (representation.equals(DestinationRep.class.toString()))
                cell.addRep(new DestinationRep(coordinates, color));
            else if (representation.equals(WallRep.class.toString()))
                cell.addRep(new SolidWallRep(coordinates));


            cells.add(cell);
        }

        return cells;
    }


    /**
     * Get the current number of memory fragments in memory of this agent.
     */
    @Override
    public int getNbMemoryFragments() {
        return this.memory.size();
    }

    /**
     * Get the maximum number of memory fragments for this agent.
     */
    @Override
    public int getMaxNbMemoryFragments() {
        return AgentImp.MAX_MEMORY_FRAGMENTS;
    }




    /**
     * Assigns a new BehaviorState to this Agent implementation
     */
    @Override
    public void setCurrentBehaviorState(BehaviorState bs) {
        lnkBehaviorState = bs;
    }












    @Subscribe
    private void handleAgentActionEvent(AgentActionEvent event) {
        if (event.getAgent() == getAgent() && EnergyValues.ENERGY_ENABLED) {
            var agent = this.getAgent();

            int energyCost = EnergyValues.calculateEnergyCost(event);

            int oldBatteryState = agent.getBatteryState();
            agent.updateBatteryState(-energyCost);
            int newBatteryState = agent.getBatteryState();


            // Throw event if energy is either increased or decreased over or under 10% threshold
            int before = (int) Math.floor((oldBatteryState * 10.0) / (double) EnergyValues.BATTERY_MAX);
            int after = (int) Math.floor((newBatteryState * 10.0) / (double) EnergyValues.BATTERY_MAX);

            if (before != after) {
                var energyEvent = new EnergyUpdateEvent(this);
                energyEvent.setAgent(agent);
                energyEvent.setEnergyPercentage(Math.max(after, before) * 10);
                energyEvent.setIncreased(after > before);

                this.eventBus.post(energyEvent);
            }

        }
    }


    /**
     * Stops this agent. See superclass. Finishes also beliefBase.
     */
    public void finish() {
        super.finish();
        memory.clear();
        memory = null;
    }




    // Other

    /**
     *Deliver the current contents of this AgentImp's mailBuffer and request for another communication-phase.
     */
    public final void continueCommunication() {
        CommunicationOutcome outcome =
                new CommunicationOutcome(getActiveItemID(), true, getSyncSet(), "CC",
                        (MailBuffer) getMailBuffer().clone());
        this.logger.fine(String.format("agentImp %d sending his communication to", getActiveItemID().getID()));

        Mail mail;
        for (int i = 0; i < outcome.getMailBuffer().getMails().length; i++) {
            mail = outcome.getMailBuffer().getMails()[i];
        this.logger.fine(String.format("\t%s with the following message: %s", mail.getTo(), mail.getMessage()));
        }

        getMailBuffer().clear();
        concludePhaseWith(outcome);
    }

    /**
     * Deliver the current contents of this AgentImp's mailBuffer and request to enter the action-phase.
     */
    public final void closeCommunication() {
        CommunicationOutcome outcome = new CommunicationOutcome(getActiveItemID(), true,
                getSyncSet(), "EOC", (MailBuffer) getMailBuffer().clone());

        this.logger.fine(String.format("agentImp %d sending his communication to: ", getActiveItemID().getID()));

        Mail mail;
        for (int i = 0; i < outcome.getMailBuffer().getMails().length; i++) {
            mail = outcome.getMailBuffer().getMails()[i];
            this.logger.fine(String.format("\t%s sends to %s the following message: %s", 
                mail.getFrom(), mail.getTo(), mail.getMessage()));
        }
        getMailBuffer().clear();
        concludePhaseWith(outcome);
    }

    /**
     * Returns true if agent is in communication phase
     *
     * @return true if agent is in communication phase, false otherwise
     */
    public boolean inCommPhase() {
        return talking;
    }

    /**
     * Returns true if agent is in action phase
     *
     * @return true if agent is in action phase, false otherwise
     */
    public boolean inActionPhase() {
        return doing;
    }

    /**
     * Triggers an update of the behavior. If necessary the agent changes
     * behavior.
     */
    public void updateBehavior() {
        this.logger.fine(String.format("Agent %s testing behaviors", this.getName()));
        this.logger.fine(String.format("Agent %s from %s", this.getName(), this.getCurrentBehavior().getClass().getName()));
        lnkBehaviorState.testBehaviorChanges();
        this.logger.fine(String.format("Agent %s to %s", this.getName(), this.getCurrentBehavior().getClass().getName()));
        BehaviorChangeEvent event = new BehaviorChangeEvent(this);
        event.setAgent(getActiveItemID());
        event.setBehaviorName(getCurrentBehavior().getClass().getSimpleName());
        this.logger.fine(event.getBehaviorName());
        this.eventBus.post(event);
    }





    /**
     * Returns a copy of the Agent Item which represents this AgentImp in the
     * Environment
     * @return getEnvironment().getAgentWorld().getAgent(getID())
     */
    private Agent getAgent() {
        return getEnvironment().getAgentWorld().getAgent(getActiveItemID());
    }
    
    /**
     * Return the buffer for **outgoing** mails of this AgentImp.
     */
    private MailBuffer getMailBuffer() {
        return outgoingMails;
    }


    /**
     * Creates the graph of behaviors and changes for this agentImplementation.
     * @post getCurrentBehavior <> null
     */
    public abstract void createBehavior();



    //INTERFACE TO SYNCHRONIZER

    protected void cleanup() {
        //finishing up
        messages.clear();
        lnkBehaviorState.finish();
        lnkBehaviorState = null;
    }

    /**
     * Adds a message to the messages queue
     */
    public void receiveMessage(Mail msg) {
        messages.add(msg);
    }



    // AGENT IMP INTERFACE TO RUNNING THREAD

    /**
     * The run cycle of the thread associated with this AgentImp.
     */
    public void run() {
        if (initialRun) {
            perceive();
            initialRun = false;
        }
        while (running) {
            checkSuspended();
            if (running) {
                if (checkSynchronize()) {
                    synchronize();
                }
                executeCurrentPhase();
            }
        }
        cleanup();
    }

    /**
     * Implements the execution of a synchronization phase.
     */
    protected void execCurrentPhase() {
        if (perceiving) {
            this.logger.fine(String.format("AgentImp %d starting perception.", getActiveItemID().getID()));
            perception();
        } else if (talking) {
            this.logger.fine(String.format("AgentImp %d starting to talk.", getActiveItemID().getID()));
            communication();
        } else if (doing) {
            this.logger.fine(String.format("AgentImp %d starting to do.", getActiveItemID().getID()));

            action();
            AgentHandledEvent event = new AgentHandledEvent(this);
            event.setAgent(this);
            this.eventBus.post(event);
        }
    }

    protected boolean environmentPermissionNeededForNextPhase() {
        return true;
    }

    /**
     * Perceive and notify Environment of the conclusion of this AgentImp's perception.
     */
    private void perception() {
        perceive();
        PerceptionOutcome outcome = new PerceptionOutcome(getActiveItemID(), true, getSyncSet());
        concludePhaseWith(outcome);
    }

    /**
     * Implements the communication phase
     */
    protected void communication() {
        updateBehavior();
        getCurrentBehavior().handle(this);
    }

    /**
     * Implements the action phase
     */
    protected void action() {
        getCurrentBehavior().handle(this);
    }


    public void resetCommittedAction() {
        this.committedAction = false;
    }

    public boolean hasCommittedAction() {
        return this.committedAction;
    }
}
