package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Random;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;

import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * Check if there are remaining sets in deck (after we remove all the cards from
     * the table to the deck)
     * if not - the game sould be finish
     */
    private boolean remainingSet;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private Thread dealerThread;
    private boolean under5; // if the timer is in the warning time
    protected Queue<Player> playersToCheck; // the plaayers that waiting for the dealer to check their sets/
    final private int DEALER_SLEEP_TIME;
    final private int DEALER_SLEEP_TIME_WARNINGTIME;
    final private int ONE_SECOND;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        under5 = false;
        playersToCheck = new ConcurrentLinkedDeque<Player>();
        remainingSet = true;
        DEALER_SLEEP_TIME = 100;
        DEALER_SLEEP_TIME_WARNINGTIME = 5;
        ONE_SECOND = 1000;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        dealerThread = Thread.currentThread();
        for (Player player : players) {
            player.setThread();
            player.getplayerThread().start();

        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            timerLoop();
            updateTimerDisplay(false);
        }
        // env.logger.log(Level.INFO, "now need to announce");
        if (!terminate) {
            announceWinners();
            terminate = true;

        }
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!shouldFinish() && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            updateTimerDisplay(false);
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {

        for (Player p : players) {

            p.terminate();
            synchronized (p) {
                p.notifyAll();
            }
            try {
                p.getplayerThread().join(); // waiting for all the players to finish.
            } catch (InterruptedException e) {
            }
        }
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || !remainingSet;
    }

    /**
     * Checks if any player waiting to be checked and then check all the parametrers
     * in the forward functions.
     */

    private void removeCardsFromTable() {
        if (!playersToCheck.isEmpty()) {
            // env.logger.log(Level.INFO, "how much before loop" + playersToCheck.size());
            for (Player p : playersToCheck) {
                synchronized (p) {
                    Player myPlayer = (Player) playersToCheck.poll();
                    // env.logger.log(Level.INFO, "Thread " + myPlayer.id + " checked now");
                    BlockingQueue<Integer> myPlayerTokens = myPlayer.getActions();
                    // env.logger.log(Level.INFO, "Thread " + myPlayer.id + "size of copy" +
                    // myPlayerTokens.size());
                    isLegalSet(myPlayer, myPlayerTokens);
                }
            }
        }

    }

    /**
     * if player have a set this funcion remove the tokens and the caards from the
     * slot and placing other card if it possible
     * the player that found the set get a point
     * this func is calles from isLegal
     */
    private void removeCardsFromTable(BlockingQueue<Integer> slots, Player player) {
        env.ui.setScore(player.id, (player.setAndGetScore()));
        synchronized (table) {
            for (Integer s : slots) {

                table.removeCard(s.intValue());

                for (Player p : players) {

                    if (p.getActions().contains(s) && !p.equals(player)) {
                        p.setActions(s);
                    }

                }
                Random rnd = new Random();
                int cardsInDeck = deck.size();
                if (cardsInDeck != 0) {
                    int cardToPlace = deck.get(rnd.nextInt((cardsInDeck)));
                    table.placeCard(cardToPlace, s);
                    deck.remove((Object) cardToPlace);
                }

            }
            // env.logger.log(Level.INFO, "dealer release table key after point");
        }

        player.setGetPoint();
        player.setToCheck();
        player.notifyAll();
        if (!player.getIsHuman()) {
            player.getAiThread().interrupt();
        }

        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    /**
     * @pre
     *      table.countCards>=0
     * @post
     *       if deck.size()>=tableSize------>table.countCards==tableSize
     *       else----->table.countCards==@pre(deck.size)
     */
    protected void placeCardsOnTable() {
        synchronized (table) {
            if (table.countCards() == 0) {
                Random rnd = new Random();
                for (int i = 0; i < table.slotToCard.length && deck.size() > 0; i++) {
                    int cardsInDeck = deck.size();
                    if (table.slotToCard[i] == null) {
                        int cardToPlace = deck.get(rnd.nextInt((cardsInDeck)));
                        table.placeCard(cardToPlace, i);
                        deck.remove((Object) cardToPlace);
                    }

                }
            }
        }
        // env.logger.log(Level.INFO, "dealer release table key after
        // placeCardsOnTable");
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try {
            if (!under5) {
                Thread.sleep(DEALER_SLEEP_TIME);
            } else {

                Thread.sleep(DEALER_SLEEP_TIME_WARNINGTIME);
            }
        } catch (InterruptedException e) {

        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {

        long d = reshuffleTime - System.currentTimeMillis() + ONE_SECOND - 1;
        if (d <= env.config.turnTimeoutWarningMillis) {
            under5 = true;
            reset = true;
        }

        if (d >= ONE_SECOND)
            env.ui.setCountdown(d, reset);
        if (d < ONE_SECOND) {
            env.ui.setCountdown(0, reset);
            removeAllCardsFromTable();

        }

    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        synchronized (table) {
            for (int i = 0; i < env.config.tableSize; i++) {
                if (table.slotToCard[i] != null) {
                    deck.add(table.slotToCard[i]);
                    table.removeCard(i);
                }
            }
            remainingSet = !(env.util.findSets(deck, 1).size() == 0); // checks if there are remaining set in the deck.
                                                                      // if not - the geme is terminate.

            // env.logger.log(Level.INFO, "is there more sets" + remainingSet + "deck size"
            // + deck.size());
            for (Player p : players) {
                p.removeAllTokens();
            }
        }
        // env.logger.log(Level.INFO, "dealer release table key after remove all card
        // from table");

    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        removeAllCardsFromTable();
        int maxScore = 0;
        int numofplayers = 0;
        for (Player player : players) {
            if (player.getScore() == maxScore) {
                numofplayers++;

            }
            if (player.getScore() > maxScore) {
                numofplayers = 1;
                maxScore = player.getScore();

            }
        }
        int[] winners = new int[numofplayers];
        int i = 0;

        for (Player p : players) {
            if (p.getScore() == maxScore) {
                winners[i] = p.id;
                i++;
            }
        }
        env.ui.announceWinner(winners);
        for (Player p : players) {

            p.terminate();
            synchronized (p) {
                p.notifyAll();
            }
            try {
                p.getplayerThread().join();
            } catch (InterruptedException e) {

            }

        }

    }

    /**
     * check if the 3 cards is a set (in a fair way)
     */
    public void isLegalSet(Player player, BlockingQueue<Integer> list) {
        if (player.getTokens() < env.config.featureSize) { // if some of my token was removed before the dealer checked
                                                           // me
            player.setToCheck();
            player.notifyAll();
            if (!player.getIsHuman()) {
                player.getAiThread().interrupt();
            }
        } else {
            synchronized (table) {
                if (player.getTokens() == env.config.featureSize) {
                    // building the arry of cards that need to be checked
                    int[] cards = new int[env.config.featureSize];
                    int i = 0;
                    int capacity = 0;
                    for (Integer s : list) {
                        if ((table.slotToCard[s]) != null) {
                            cards[i] = (table.slotToCard[s]).intValue();
                            i++;
                            capacity++;
                        }
                    }
                    //
                    if (capacity == env.config.featureSize) { // double check that all the token remains
                        boolean ans = env.util.testSet(cards);
                        if (ans) { // point
                            removeCardsFromTable(list, player);
                        } else { // penalty
                            player.setInPenalty();
                            player.setToCheck();
                            player.notifyAll();
                            if (!player.getIsHuman()) {
                                player.getAiThread().interrupt();
                            }
                        }
                    } else { // if capacity<3 release the player without any penalty
                        player.setToCheck();
                        player.notifyAll();
                        if (!player.getIsHuman()) {
                            player.getAiThread().interrupt();
                        }

                    }
                }
            }
        }
        // env.logger.log(Level.INFO, "dealer release table key after islegal func");

    }

    public Thread getDealerThread() {
        return dealerThread;
    }

    /**
     * @pre:
     *       playersToCheck.size()>=0
     * @post
     *       playersToCheck.size()==@pre(playersToCheck.size())+1
     */

    public void setPlayersToCheck(Player player) {
        if (!playersToCheck.contains(player)) {
            playersToCheck.add(player);
            // env.logger.log(Level.INFO, "how much players to check " +
            // playersToCheck.size());
        }

    }

    public long getReshuffleTime() {
        return reshuffleTime;
    }

}
