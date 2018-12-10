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
        // Bidding history of the opponent
        BidHistory bidHistory;
        // Number of issues in the domain
        Integer num_issues;
        // IDs of the issues
        int[] issues_id;
        // Values of the issues
        EvaluatorDiscrete[] issuesEvaluator;

        /**
         * Constructor
         *
         * @param bid A bid used to populate the issue space
         */
        public Opponent(Bid bid) {
            this.bidHistory = new BidHistory();
            this.num_issues = bid.getIssues().size();
            this.issues_id = new int[this.num_issues];

            // Assign the issue IDs
            for (int i = 0; i < bid.getIssues().size(); i++) {
                this.issues_id[i] = bid.getIssues().get(i).getNumber();
            }

            // Create the evaluators for each issue
            this.issuesEvaluator = new EvaluatorDiscrete[this.num_issues];
            for (int i = 0; i < this.num_issues; i++) {
                this.issuesEvaluator[i] = new EvaluatorDiscrete();
            }
        }

        /**
         * Add a bid to the opponent's bidding history
         *
         * @param bid The bid to add to the history
         */
        public void addBid(Bid bid) {
            this.bidHistory.add(new BidDetails(bid, 0)); //初始化了bid和myUndiscountedUtil
            this.setWeights();
        }

        /**
         * Computes the estimated utility for the opponent for a given bid
         *
         * @param bid The bid of which we want to estimate the utility for the opponent
         * @return estimated utility for the given bid
         */
        public double getOpponentUtility(Bid bid) { //***********************估算对手的utility***********************
            double U = 0.0;

            HashMap<Integer, Value> bid_values = bid.getValues();
            // Iterate over the issues
            for (int i = 0; i < this.num_issues; i++) {
                // Get weight of current issue
                double weight = this.issuesEvaluator[i].getWeight();

                ValueDiscrete value = (ValueDiscrete) bid_values.get(this.issues_id[i]);

                if ((this.issuesEvaluator[i]).getValues().contains(value)) {
                    U += this.issuesEvaluator[i].getDoubleValue(value).doubleValue() * weight;
                }
            }
            return U;
        }

        /**
         * Set the weights of the issues and the values per issue
         */
        public void setWeights() {
            this.setIssuesWeight();
            this.setValuesWeight();
        }

        /**
         * Set the weights for the values of each issues using frequency analysis
         */
        private void setValuesWeight() {
            // Iterate over the issues
            for (int i = 0; i < this.num_issues; i++) {
                // The keys of the map are the possible values of the issues and the values of the map
                // are the times each value of the issue was used
                HashMap<ValueDiscrete, Double> values = new HashMap<ValueDiscrete, Double>();
                // Iterate over the bidding history
                for (int j = 0; j < this.bidHistory.size(); j++) {
                    ValueDiscrete value = (ValueDiscrete) (this.bidHistory.getHistory().get(j).getBid()
                            .getValue(this.issues_id[i]));
                    if (values.containsKey(value)) {
                        values.put(value, values.get(value) + 1);
                    } else {
                        values.put(value, 1.0);
                    }
                }

                // Get the maximum number of times a value was used
                double max_times = 0.0;
                for (ValueDiscrete value : values.keySet()) {
                    if (values.get(value) > max_times)
                        max_times = values.get(value);
                }

                // Set the evaluation values of each issue as the number of times each value was used
                // divided by the maximum number of times a value was used (so that the max is 1)
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

            //****************************计算对手每一个issue的weight*******************************
            // Iterate over all the issues
            for (int i = 0; i < this.num_issues; i++) {
                weights[i] = 1.0 / this.num_issues + (this.bidHistory.size() - changeTimes[i] - 1) / 10.0;
                // Keep the total weight to normalize
                totalWeight += weights[i];
            }

            // Normalize the weights of the issues
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
        public Double hardChanges(int rounds) { //*************************计算对手的硬度*****************************

            if (this.bidHistory.size() < rounds) { //确保对手已经出价
                return null;
            }

            //记录对手改变次数，返回硬度
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
    private double timeRange_MaxUtility = 0.20D; //*********** 提供最大utility offer的时间 **********
    // Minimum utility that will be offered or accepted
    private double reservation_Value = 0.4D; //************ 最小接受的utility **************
    // The maximum number of the good bids will be saved
    private int max_Num_GoodBits = 100;

    // Used to find the closed bid with a given utility
    private SortedOutcomeSpace sortedOutSpace;//**********是否可用***********
    private Random randomGenerator;
    // A list to store the good bids that provided by opponent(*******储存对手的bids*******)
    private List<BidDetails> goodBidsList = new ArrayList<BidDetails>();

    int num_Round; //The number of rounds for negotiation

    //Build a list ro rank the stored bids
    List<Bid> rankedBids = new ArrayList<Bid>();
    AbstractUtilitySpace utilitySpace;
    AdditiveUtilitySpace additiveUtilitySpace;

    @Override
    public void init(NegotiationInfo info) {
        super.init(info);

        //Initial self modeling
        utilitySpace = estimateUtilitySpace_a10(); //************** 相当于对自己建模初始化 **************

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
        System.out.println(num_Round);//输出进行多少轮

        // Lets start with our maximum utility bid and keep it for a range of time, because we are bad boys
        if (inMaxUtilityTime()) { //******************在这个时间段内一直产生最大utility offer*********************
            Bid max_Bid = null;
            try {
                max_Bid = rankedBids.get(rankedBids.size() - 1); //获取最大utility offer
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Cannot generate max utility bid");
            }
            System.out.println("It's max utility bid time!");
            return new Offer(getPartyId(), max_Bid);
        }

        //System.out.format("Last received bid had utility of [%f] for me%n", getUtility(this.lastReceivedBid));

        // Generate an offer
        Bid my_Offer = generateBid();

        // Check if the latest offer should be accepted, given the offer
        if (doAccept(my_Offer)) {
         //   System.out.println("I'm going to accept the latest offer!");
            return new Accept(getPartyId(), this.lastReceivedBid);
        }

        // Offer the bid
        //System.out.format("I'm going to offer the bid that I generated, which has utility [%f]%n", additiveUtilitySpace.getUtility(my_Offer));
        return new Offer(getPartyId(), my_Offer);
    }

    /**
     * Compute minimum acceptable utility at the current moment
     *
     * @return double acceptable minimum utility
     */
    private double acceptableUtility() {
        double left_time = 1 - getTimeLine().getTime();

        // At the beginning of the negotiation the minimum utility will be 0.9,
        //*******************根据剩下的时间不同，最小接受的utility可以改变**********************
        double min_utility = Math.log10(left_time) / this.changingFactor() + 0.9D;

        return Math.max(min_utility, this.reservation_Value); //返回可接受的utility和最小utility中大的一个
    }

    /**
     * Compute the changing factor. If one of the opponents are hard headed concede faster
     *
     * @return double indicating the conceding factor
     */
    private double changingFactor() { //****************计算出让步因素********************
        Double max_changtimes = 0.0;
        Double current_changtimes;
        for (AgentID id : this.opponentsMap.keySet()) { //*******this.opponentsMap.keySet()表示多个opponent**************
            // Get how hard that agent changes considering last 40 rounds
            current_changtimes = this.opponentsMap.get(id).hardChanges(40); //**********每40轮判断一次***********
            if (current_changtimes != null && current_changtimes > max_changtimes) {
                max_changtimes = current_changtimes;
            }
        }

        //**************************************************************************************************************
        if (this.timeline.getTime() < 0.96D) {
            return 13;
        }
        // Check whether at least one agent is hard headed
        // If so we should concede faster
        if (max_changtimes > 0.6) {
            return 7.0;
        } else {
            return 10.0;
        }
        //**************************************************************************************************************
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

        //System.out.format("Min utility: [%f]%n", minUtility);
        // Accept the latest offer if it has a utility higher than the utility of the bid will be offered later,
        // or if the utility is higher than our minUtility
        return (do_accept || getUtility(this.lastReceivedBid) > min_utility);
    }

    /**
     * Determines if the time is in the range of first part which was defined before
     *
     * @return boolean indicating if keeping offer the maximum utility offer
     */
    private boolean inMaxUtilityTime() {
        return getTimeLine().getTime() < this.timeRange_MaxUtility; //现在的时间小于之前定义的最大utility的时间
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

        } while (getUtility(acceptable_bid) <= min_acceptable_utility); //确保产生bid的utility大于目前最小期望utility值
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

        double acceptableUtility = this.acceptableUtility(); //Calculate the acceptable utility for this moment***********
        Bid generatedBid = acceptableRandomBid(acceptableUtility); //Generate a bid which has utility higher than minimum utility
        double nashPointValue = 0.0; //Initial a nash point
        double nashPredict; //for predict a better nash point

        //************************************************************************************************************
        if (this.goodBidsList.size() >= 100 && this.num_Round % 20 == 0) { //The round bigger than 100 times，and each 20 round
            this.evaluateUtilities(); //set myUndiscountedUtil which is nash value
        }
        //************************************************************************************************************

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

            // *******************************随机产生最后五个bids中的其中一个******************************
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

    public AbstractUtilitySpace estimateUtilitySpace_a10() {
        //定义一个double型的二维数组，用来存放K值
        //Mun[m][n],m是issue的个数，n是value的个数
        double Num[][];
        //获取进来的domain
        Domain domain = getDomain();
        //根据domain生成factory
        AdditiveUtilitySpaceFactory factory = new AdditiveUtilitySpaceFactory(domain);
        //usermodel生成一个BidRanking，里面都是一个个bid,放到r里
        BidRanking r = userModel.getBidRanking();
        //计算一共有多少个bid,赋值给totalnum
        int totalnum = r.getBidOrder().size();
        System.out.println("totalnum:" + totalnum);
        double points = 0;
        for (Bid b : r.getBidOrder()) {

            List<Issue> issues = b.getIssues();
            int m = issues.size();
//            List<Issue> issuesList = bid.getIssues();
            //           System.out.println("this is Bid "+b.getValue(1)+" "+b.getValue(2)+" "+b.getValue(3));
            for (Issue issue : issues) {
                //int n=issue
                System.out.println(issue.getName() + ": " + b.getValue(issue.getNumber()));
                System.out.println(issue.getNumber() + "*****************************");
            }

            /////////////////////////////////////////
            for (Issue i : issues) {
                int no = i.getNumber();
                ValueDiscrete v = (ValueDiscrete) b.getValue(no);
                double oldUtil = factory.getUtility(i, v);
//                System.out.println("old utility of "+i.getName()+i.getNumber()+": "+oldUtil);
                factory.setUtility(i, v, oldUtil + points);
            }
            points += 1;
        }
        factory.normalizeWeightsByMaxValues();

        return factory.getUtilitySpace();
//        return new AdditiveUtilitySpaceFactory(getDomain()).getUtilitySpace();

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
