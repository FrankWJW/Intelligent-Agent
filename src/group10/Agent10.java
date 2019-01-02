package group10;

import genius.core.AgentID;
import genius.core.Bid;
import genius.core.BidHistory;
import genius.core.Domain;
import genius.core.actions.Accept;
import genius.core.actions.Action;
import genius.core.actions.Offer;
import genius.core.issue.Issue;
import genius.core.issue.Value;
import genius.core.issue.ValueDiscrete;
import genius.core.parties.AbstractNegotiationParty;
import genius.core.parties.NegotiationInfo;
import genius.core.bidding.BidDetails;
import genius.core.boaframework.SortedOutcomeSpace;
import genius.core.uncertainty.AdditiveUtilitySpaceFactory;
import genius.core.uncertainty.BidRanking;
import genius.core.utility.AbstractUtilitySpace;
import genius.core.utility.AdditiveUtilitySpace;
import genius.core.utility.EvaluatorDiscrete;

import java.util.*;

/**
 * Agent 10 offers the bid with maximum utility for half of the negotiation.
 * In the second half, it offers a random bid, which has utility higher than minimum expected utility.
 * It only accepts offer if the utility of the bid is higher than Agent's last offered bid.
 */
public class Agent10 extends AbstractNegotiationParty {
    private final String description = "Agent10";

    // *************************************************************************************************************
    // *************************** Private Class used to predict the opponent model ********************************
    // *************************************************************************************************************
    private class Opponent {
        BidHistory bidHistory;
        Integer num_issues;
        int[] issues_id;
        EvaluatorDiscrete[] issuesEvaluator;


        public Opponent(Bid bid) {
            this.bidHistory = new BidHistory();
            this.num_issues = bid.getIssues().size();
            this.issues_id = new int[this.num_issues];

            for (int i = 0; i < bid.getIssues().size(); i++) {
                this.issues_id[i] = bid.getIssues().get(i).getNumber();
            }

            this.issuesEvaluator = new EvaluatorDiscrete[this.num_issues];
            for (int i = 0; i < this.num_issues; i++) {
                this.issuesEvaluator[i] = new EvaluatorDiscrete();
            }
        }

        public void addBid(Bid bid) {
            this.bidHistory.add(new BidDetails(bid, 0));
            this.setWeights();
        }

        public double getOpponentUtility(Bid bid) {
            double U = 0.0;

            HashMap<Integer, Value> bid_values = bid.getValues();
            // Iterate over the issues
            for (int i = 0; i < this.num_issues; i++) {

                double weight = this.issuesEvaluator[i].getWeight();

                ValueDiscrete value = (ValueDiscrete) bid_values.get(this.issues_id[i]);

                if ((this.issuesEvaluator[i]).getValues().contains(value)) {
                    U += this.issuesEvaluator[i].getDoubleValue(value).doubleValue() * weight;
                }
            }
            return U;
        }

        public void setWeights() {
            this.setIssuesWeight();
            this.setValuesWeight();
        }

        private void setValuesWeight() {
            // Iterate over the issues
            for (int i = 0; i < this.num_issues; i++) {

                HashMap<ValueDiscrete, Double> values = new HashMap<ValueDiscrete, Double>();

                for (int j = 0; j < this.bidHistory.size(); j++) {
                    ValueDiscrete value = (ValueDiscrete) (this.bidHistory.getHistory().get(j).getBid()
                            .getValue(this.issues_id[i]));
                    if (values.containsKey(value)) {
                        values.put(value, values.get(value) + 1);
                    } else {
                        values.put(value, 1.0);
                    }
                }

                double max_times = 0.0;
                for (ValueDiscrete value : values.keySet()) {
                    if (values.get(value) > max_times)
                        max_times = values.get(value);
                }

                for (ValueDiscrete value : values.keySet()) {
                    try {
                        this.issuesEvaluator[i].setEvaluationDouble(value, values.get(value) / max_times);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        /**
         * Set the weights for each issue using frequency analysis
         */
        private void setIssuesWeight() {
            int[] changeTimes = getChangesTimes(this.bidHistory.size());
            double[] weights = new double[this.num_issues];
            double totalWeight = 0.0;


            for (int i = 0; i < this.num_issues; i++) {
                weights[i] = 1.0 / this.num_issues + (this.bidHistory.size() - changeTimes[i] - 1) / 10.0;
                totalWeight += weights[i];
            }

            for (int i = 0; i < this.num_issues; i++) {
                this.issuesEvaluator[i].setWeight(weights[i] / totalWeight);
            }
        }

        /**
         * Return an array which represents the frequency of change of each issue only considering the last $rounds rounds
         *
         * @return an array in which each elements represents the number of changes of that issue
         */
        private int[] getChangesTimes(int rounds) {
            // In this array we store the number of times each issue has changed
            int[] num_changeTimes = new int[this.num_issues];

            // Iterate over all the issues
            for (int i = 0; i < this.num_issues; i++) {
                Value oldValue = null;
                Value currentValue;
                int number = 0;

                // Iterate over the last rounds bids from the bidding history
                for (int j = this.bidHistory.size() - 1; j > this.bidHistory.size() - rounds - 1; j--) {
                    currentValue = this.bidHistory.getHistory().get(j).getBid().getValue(this.issues_id[i]);

                    // If it's not the first value and the current value is different from the previous one,
                    // it means it has changed. We then increment the changes count.
                    if (oldValue != null && !oldValue.equals(currentValue)) {
                        number++;
                    }

                    oldValue = currentValue;
                }
                num_changeTimes[i] = number;
            }
            return num_changeTimes;
        }

        /**
         * Return how hard the agent is. 1 = bids do not change in the last $rounds,
         * 0 = bid change every time in the last $rounds, only consider the last $rounds rounds
         *
         * @param
         * @return range 0-1
         */
        public Double hardChanges(int rounds) {

            if (this.bidHistory.size() < rounds) {
                return null;
            }

            int[] changeTimes = this.getChangesTimes(rounds);
            int sum = 0;
            for (int times : changeTimes) {
                sum += times;
            }
            return 1 - (sum / (double) this.num_issues) / (double) rounds;
        }
    }


    // *************************************************************************************************************
    // *************************************************************************************************************

    private Bid lastReceivedBid = null; // Latest received bid that offered by the opponents

    private HashMap<AgentID, Opponent> opponentsMap; // Hash map of the opponent model

    // The range of time to keep offering the maximum utility bid
    private double timeRange_MaxUtility = 0.20D;
    // Minimum utility that will be offered or accepted
    private double reservation_Value = 0.4D;
    private double final_Value = 0.5D;
    // The maximum number of the good bids will be saved
    private int max_Num_GoodBits = 100;


    // Used to find the closed bid with a given utility
    private SortedOutcomeSpace sortedOutSpace;
    private Random randomGenerator;
    // A list to store the good bids that provided by opponent
    private List<BidDetails> goodBidsList = new ArrayList<BidDetails>();

    int num_Round; //The number of rounds for negotiation
    double predict_totalRound = 0;
    boolean finalAccept = false;

    //Build a list ro rank the stored bids
    List<Bid> rankedBids = new ArrayList<Bid>();
    AbstractUtilitySpace utilitySpace;
    AdditiveUtilitySpace additiveUtilitySpace;

    @Override
    public void init(NegotiationInfo info) {
        super.init(info);

        //Initial self modeling
        utilitySpace = estimateUtilitySpace_a10();

        additiveUtilitySpace = (AdditiveUtilitySpace) utilitySpace;
        this.opponentsMap = new HashMap<AgentID, Opponent>(); //Initial opponent modeling
        this.sortedOutSpace = new SortedOutcomeSpace(this.utilitySpace);
        this.randomGenerator = new Random();
        rankedBids = userModel.getBidRanking().getBidOrder(); // Initial bid ranking from small utility to big utility
    }

    /**
     * When this function is called, it is expected that the Party chooses one of the actions from the possible
     * action list (accept or offer) and returns an instance of the chosen action.
     *
     * @param list
     * @return The chosen action.
     */
    @Override
    public Action chooseAction(List<Class<? extends Action>> list) {
        this.num_Round++;
        if (178.0 < this.getTimeLine().getCurrentTime() && this.getTimeLine().getCurrentTime()< 178.05){
            this.predict_totalRound=(this.num_Round/178)*180;
            System.out.println("Predict total round: "+predict_totalRound);
        }

        // The final 10 rounds
        finalAccept = ((this.predict_totalRound - this.num_Round) < 10)&&(predict_totalRound != 0);
        if (finalAccept){
            System.out.println("Final Warning!!!!!!!!!!!!!!");
        }

        // Lets start with our maximum utility bid and keep it for a range of time, because we are bad boys
        if (inMaxUtilityTime()) {
            Bid max_Bid = null;
            try {
                max_Bid = rankedBids.get(rankedBids.size() - 1);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return new Offer(getPartyId(), max_Bid);
        }

        // Generate an offer
        Bid my_Offer = generateBid();

        // Check if the latest offer should be accepted, given the offer
        if (doAccept(my_Offer)) {
            return new Accept(getPartyId(), this.lastReceivedBid);
        }

        return new Offer(getPartyId(), my_Offer);
    }

    /**
     * Compute minimum acceptable utility at the current moment
     *
     * @return double acceptable minimum utility
     */
    private double acceptableUtility() {
        double left_time = 1 - getTimeLine().getTime();

        double min_utility = Math.log10(left_time) / this.changingFactor() + 0.9D;

        return Math.max(min_utility, this.reservation_Value);
    }

    /**
     * Compute the changing factor. If one of the opponents are hard headed concede faster
     *
     * @return double indicating the conceding factor
     */
    private double changingFactor() {
        Double max_changtimes = 0.0;
        Double current_changtimes;
        for (AgentID id : this.opponentsMap.keySet()) {
            // Get how hard that agent changes considering last 40 rounds
            current_changtimes = this.opponentsMap.get(id).hardChanges(40);
            if (current_changtimes != null && current_changtimes > max_changtimes) {
                max_changtimes = current_changtimes;
            }
        }

        // Set changing factors based on time
        if (this.timeline.getTime() < 0.96D) {
            return 20;
        }
        // If the agent is hard changing we should change faster
        if (max_changtimes > 0.6) {
            return 7.0;
        } else {
            return 10.0;
        }
    }

    /**
     * Determines if we should accept the latest bid provided by opponent
     *
     * @param my_Offer The bid that we're going to offer
     * @return boolean indicating whether we should accept the latest bid
     */
    private boolean doAccept(Bid my_Offer) {
        // Check if the utility of the latest received offer is higher than the utility of the bid will be offered later
        boolean do_accept = getUtility(this.lastReceivedBid) >= getUtility(my_Offer);
        // Get the minimum acceptable utility
        double min_utility = this.acceptableUtility();
        boolean have_to_accept = false;
        if (finalAccept){
            have_to_accept = getUtility(this.lastReceivedBid) >= final_Value;
        }


        return (do_accept || getUtility(this.lastReceivedBid) > min_utility || have_to_accept);
    }

    /**
     * Determines if the time is in the range of first part which was defined before
     *
     * @return boolean indicating if keeping offer the maximum utility offer
     */
    private boolean inMaxUtilityTime() {
        return getTimeLine().getTime() < this.timeRange_MaxUtility;
    }

    /**
     * Receive the offer and store it
     *
     * @param opponent The agent that did the action.
     * @param action The action that agent did.
     */
    @Override
    public void receiveMessage(AgentID opponent, Action action) {
        super.receiveMessage(opponent, action);

        // Receiving an offer
        if (opponent != null && action instanceof Offer) {
            this.lastReceivedBid = ((Offer) action).getBid();

            if (opponentsMap.containsKey(opponent)) {
                opponentsMap.get(opponent).addBid(this.lastReceivedBid); // Storing the received bid
            } else { // If it's the first round of negotiation, create a new opponent map
                try {
                    Opponent new_Opponent = new Opponent(generateRandomBid());
                    new_Opponent.addBid(this.lastReceivedBid);
                    opponentsMap.put(opponent, new_Opponent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Generates a random bid that has an higher utility than excepted utility
     *
     * @return the random bid with utility higher than the reservation value
     */
    private Bid acceptableRandomBid(double min_acceptable_utility) {
        Bid acceptable_bid;
        do {
            double randomNumber = this.randomGenerator.nextDouble();

            // calculate a utility based on the minimum utility and a 0-1 random number
            double utility = min_acceptable_utility + randomNumber * (1.0 - min_acceptable_utility);
            // Generate a efficient bid
            acceptable_bid = sortedOutSpace.getBidNearUtility(utility).getBid();

        } while (getUtility(acceptable_bid) <= min_acceptable_utility);
        return acceptable_bid;
    }

    /**
     * Generates a new bid basing on our own utility and the estimated utility
     * of the opponents, through frequency analysis
     *
     * @return the generated bid, which has always a utility higher than our reservation value
     */
    private Bid generateBid() {
        Bid randomBid;

        double acceptableUtility = this.acceptableUtility(); //Calculate the acceptable utility for this moment
        Bid generatedBid = acceptableRandomBid(acceptableUtility); //Generate a bid which has utility higher than minimum utility
        double nashPointValue = 0.0; //Initial a nash point
        double nashPredict; //for predict a better nash point

        if (this.goodBidsList.size() >= 100 && this.num_Round % 20 == 0) { //The round bigger than 100 times，and each 20 round
            this.evaluateUtilities(); //set myUndiscountedUtil which is nash value
        }
        for (int i = 0; i < 100; i++) {
            // Generate a random bid, which has utility higher than minimum utility
            randomBid = acceptableRandomBid(acceptableUtility);

            nashPredict = this.getNashPoint(randomBid); //calculate the nash point = own utility * opponent's utility
            // save the best bid
            if (nashPredict > nashPointValue) { //if predicted nash value higher than before
                generatedBid = randomBid;
                nashPointValue = nashPredict;
            }
        }

        if (this.goodBidsList.size() < max_Num_GoodBits) {
            this.goodBidsList.add(new BidDetails(generatedBid, nashPointValue));

            if (this.goodBidsList.size() == this.max_Num_GoodBits) {
                this.sortBids(); //Sort the bestGeneratedBids list based on utility for each bid
            }
        } else {
            // The last bid's utility is smallest
            double worstBidsUtility = this.goodBidsList.get(this.max_Num_GoodBits - 1).getMyUndiscountedUtil();
            // If new Bid is better than save it and remove the worst bid
            if (nashPointValue > worstBidsUtility) { //The best utility for that moment
                this.goodBidsList.remove(0);
                this.goodBidsList.add(new BidDetails(generatedBid, nashPointValue));
                this.sortBids(); //Sort bestGeneratedBids
            }
        }

        // When enough bids are saved, offer one of the best bids
        if (this.goodBidsList.size() >= this.max_Num_GoodBits) {
            int index = this.max_Num_GoodBits - randomGenerator.nextInt(5) - 1;// random int 0～4
            generatedBid = this.goodBidsList.get(index).getBid();
        }
        return generatedBid;
    }

    private void sortBids() {
        Collections.sort(this.goodBidsList, new Comparator<BidDetails>() {
            @Override
            public int compare(BidDetails bid1, BidDetails bid2) {
                if (bid1.getMyUndiscountedUtil() < bid2.getMyUndiscountedUtil()) {
                    return -1;
                } else if (bid1.getMyUndiscountedUtil() == bid2.getMyUndiscountedUtil()) {
                    return 0;
                } else {
                    return 1;
                }
            }
        });
    }

    private void evaluateUtilities() {
        for (BidDetails bidDetails : this.goodBidsList) {
            bidDetails.setMyUndiscountedUtil(this.getNashPoint(bidDetails.getBid())); //使用nash value设置myUndiscountedUtil
        }
    }

    private double getNashPoint(Bid bid) {
        double nashValue = this.getUtility(bid); //Get the own utility for this bid
        for (AgentID agent : this.opponentsMap.keySet()) {
            nashValue *= this.opponentsMap.get(agent).getOpponentUtility(bid); //nash value = own utility * opponent's utility
        }
        return nashValue;
    }

    //Self modeling
    public AbstractUtilitySpace estimateUtilitySpace_a10() {
        Domain domain = getDomain();
        AdditiveUtilitySpaceFactory factory = new AdditiveUtilitySpaceFactory(domain);
        BidRanking r = userModel.getBidRanking(); //对给定bid进行排序
        int totalnum = r.getBidOrder().size();
        System.out.println("totalnum:" + totalnum);
        double points = 0;
        for (Bid b : r.getBidOrder()) {

            List<Issue> issues = b.getIssues();
            for (Issue issue : issues) {
                System.out.println(issue.getName() + ": " + b.getValue(issue.getNumber()));
                System.out.println(issue.getNumber() + "*****************************");
            }

            for (Issue i : issues) {
                int no = i.getNumber();
                ValueDiscrete v = (ValueDiscrete) b.getValue(no);
                double oldUtil = factory.getUtility(i, v);
                factory.setUtility(i, v, oldUtil + points);
            }
            points += 1;
        }
        factory.normalizeWeightsByMaxValues();

        return factory.getUtilitySpace();

    }

    /**
     * A human-readable description for this party.
     * @return
     */
    @Override
    public String getDescription() {
        return description;
    }

}
