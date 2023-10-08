package bguspl.set.ex;

//import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Level;
import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;
    private final Dealer dealer;

    /**
     * Game entities.
     */

    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private BlockingQueue<Integer> actions;
    private volatile boolean isCheck; // the dealer checked me
    private volatile boolean inPenalty;
    private volatile boolean getPoint;
    private volatile boolean flag; // flag that I'm waiting for the dealer to check me
    final private int AI_WAIT_TO_CHECK;
    final private int ACCURATE_TIMER;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        this.actions = new LinkedBlockingDeque<Integer>(env.config.featureSize);
        this.terminate = false;
        this.isCheck = true;
        this.inPenalty = false;
        this.getPoint = false;
        this.flag = false;
        this.AI_WAIT_TO_CHECK = 200;
        this.ACCURATE_TIMER = 100;
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + "starting.");
        if (!human)
            createArtificialIntelligence();
        while (!terminate) {
            if (flag) {
                // env.logger.log(Level.INFO, id + " ischeck is" + isCheck);
                synchronized (this) {
                    while (!isCheck) {
                        try {
                            // env.logger.log(Level.INFO, id + " inside wait");
                            wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                // env.logger.log(Level.INFO, id + " outfrom sleep");
                synchronized (this) {
                    if (inPenalty) {
                        // env.logger.log(Level.INFO, id + " in penalty");
                        penalty();
                    } else if (getPoint) {
                        // env.logger.log(Level.INFO, id + " in point");
                        point();
                    }
                    flag = false; // keep playing (dont need to wait for the dealer to check me)
                }
            }
        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                int sizeTable = env.config.tableSize;
                int aiSlot = (int) (Math.random() * sizeTable);
                keyPressed(aiSlot);
            }

            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;

        if (!human) {
            aiThread.interrupt();

        }
        synchronized (this) {
            isCheck = true;
            this.notifyAll();
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (!inPenalty && !getPoint && isCheck && !flag) { // the player can make actions
            if (table.removeToken(id, slot) == true) { // the player intended to remove the token when he chose this
                                                       // slot
                actions.remove((Object) slot);
            }

            else if (actions.size() < env.config.featureSize) { // the player intended to place a token when he chose
                                                                // this slot
                synchronized (this) {
                    if (table.placeToken(id, slot)) { // if the player chose a slot with a card
                        actions.add(slot);
                    }
                    if (actions.size() == env.config.featureSize) { // update all the relevant parameters that im
                                                                    // waiting to be checked
                        flag = true;
                        isCheck = false;
                        dealer.setPlayersToCheck(this);
                        dealer.getDealerThread().interrupt();
                        if (!human) {
                            try {
                                Thread.sleep(AI_WAIT_TO_CHECK);
                            } catch (InterruptedException e) {
                            }
                        }
                    }
                }

            }

        }

    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        long dd = env.config.pointFreezeMillis + System.currentTimeMillis();
        while (dd - System.currentTimeMillis() >= env.config.pointFreezeMillis) {
            env.ui.setFreeze(id, dd - System.currentTimeMillis());
            try {
                Thread.sleep(Math.max(0, (int) (env.config.pointFreezeMillis - ACCURATE_TIMER)));
            } catch (InterruptedException e) {

            }

        }
        env.ui.setFreeze(id, -ACCURATE_TIMER);
        actions.clear();
        getPoint = false;
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests

    }

    /**
     * Penalize a player and perform other related actions.
     */
    /**
     * @pre
     *      action.size()==featureSize
     * @post
     *       action.size()==@pre(action.size())
     */
    public void penalty() {
        long dd = env.config.penaltyFreezeMillis + System.currentTimeMillis();
        while (dd - System.currentTimeMillis() >= env.config.pointFreezeMillis) {
            env.ui.setFreeze(id, dd - System.currentTimeMillis());
            try {
                Thread.sleep(Math.max(0, (int) (env.config.pointFreezeMillis - ACCURATE_TIMER)));
            } catch (InterruptedException e) {
            }

        }
        env.ui.setFreeze(id, -ACCURATE_TIMER);
        inPenalty = false;
    }

    public int getScore() {
        return score;
    }

    public int setAndGetScore() {
        score++;
        return score;
    }

    public int getTokens() {
        return actions.size();
    }

    public BlockingQueue<Integer> getActions() {
        return actions;
    }

    public void setActions(int slot) {
        actions.remove((Object) slot);
    }

    public void addActions(int slot) {
        actions.add(slot);
    }

    public Thread getplayerThread() {
        return playerThread;
    }

    public void setThread() {
        playerThread = new Thread(this, "" + id);
    }

    /**
     * @pre:
     *       action.size()>=0
     * @post
     *       action.size==0
     */

    public void removeAllTokens() {
        actions.clear();

    }

    public void setToCheck() {
        isCheck = true;
        // env.logger.log(Level.INFO, id + "set check to true" + "" + isCheck);
    }

    public void setInPenalty() {
        inPenalty = true;
        // env.logger.log(Level.INFO, id + "set penalty to true" + "" + inPenalty);
    }

    public synchronized boolean getInPenalty() {
        return inPenalty;
    }

    public synchronized void setGetPoint() {
        getPoint = true;
        // env.logger.log(Level.INFO, id + "set get pointtrue" + "" + getPoint);
    }

    public synchronized boolean getGetPoint() {
        return getPoint;
    }

    public Thread getAiThread() {
        return aiThread;
    }

    public boolean getIsHuman() {
        return human;
    }

}
