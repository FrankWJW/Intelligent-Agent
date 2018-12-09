import genius.core.AgentID;
import genius.core.Bid;
import genius.core.Domain;
import genius.core.actions.Accept;
import genius.core.actions.Action;
import genius.core.actions.Offer;
import genius.core.issue.Issue;
import genius.core.issue.ValueDiscrete;
import genius.core.parties.AbstractNegotiationParty;
import genius.core.parties.NegotiationInfo;
import genius.core.bidding.BidDetails;
import genius.core.boaframework.SortedOutcomeSpace;
import genius.core.uncertainty.AdditiveUtilitySpaceFactory;
import genius.core.uncertainty.BidRanking;
import genius.core.utility.AbstractUtilitySpace;
import genius.core.utility.AdditiveUtilitySpace;

import java.util.*;

/**
 * ExampleAgent returns the bid that maximizes its own utility for half of the negotiation session.
 * In the second half, it offers a random bid. It only accepts the bid on the table in this phase,
 * if the utility of the bid is higher than Example Agent's last bid.
 */
public class Agent10 extends AbstractNegotiationParty {
    private final String description = "Agent10";
    // Latest bid received by the opponents
    private Bid lastReceivedBid = null;

    // Map of the opponents
    private HashMap<AgentID, Opponent> opponentsMap;
    // Percentage of time in which we'll just keep offering the maximum utility bid
    private double TIME_OFFERING_MAX_UTILITY_BID = 0.20D;
    // Utility above which all of our offers will be
    private double RESERVATION_VALUE = 0.4D;
    // The max amount of the best bids to save
    private int maxAmountSavedBits = 100;
    // This is used to compute the closed bid for a given utility
    private SortedOutcomeSpace SOS;
    private Random randomGenerator;
    // The best bids found while searching are saved here
    private List<BidDetails> bestGeneratedBids = new ArrayList<BidDetails>();

    int turn;

    int count=0;


    //为Bid建一个list,里面放Bid
    List<Bid> rankedBids= new ArrayList<Bid>();
    AbstractUtilitySpace utilitySpace;
    AdditiveUtilitySpace additiveUtilitySpace;
    @Override
    public void init(NegotiationInfo info) {
        // The class variables are initialized
        super.init(info);
        utilitySpace = estimateUtilitySpace_a10();
        additiveUtilitySpace = (AdditiveUtilitySpace) utilitySpace;
        this.opponentsMap = new HashMap<AgentID, Opponent>();
        this.SOS = new SortedOutcomeSpace(this.utilitySpace);
        this.randomGenerator = new Random();
        rankedBids = userModel.getBidRanking().getBidOrder();
    }

    /**
     * Each round this method gets called and ask you to accept or offer. The
     * first party in the first round is a bit different, it can only propose an
     * offer.
     *
     * @param validActions Either a list containing both accept and offer or only offer.
     * @return The chosen action.
     */
    @Override
    public Action chooseAction(List<Class<? extends Action>> validActions) {
        this.turn++;
        // For the first part of the negotiation, just keep offering the maximum
        // utility bid
        if (isMaxUtilityOfferTime()) {
            Bid maxUtilityBid = null;
            try {
                maxUtilityBid = rankedBids.get(rankedBids.size() - 1);
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Cannot generate max utility bid");
            }
            System.out.println("It's max utility bid time!");
            return new Offer(getPartyId(), maxUtilityBid);
        }

        System.out.format("Last received bid had utility of [%f] for me%n", getUtility(this.lastReceivedBid));

        // Generate a acceptable bid
        Bid proposedBid = generateBid();

        // Check if we should accept the latest offer, given the bid we're
        // proposing
        if (isAcceptable(proposedBid)) {
            System.out.println("I'm going to accept the latest offer!");
            return new Accept(getPartyId(), this.lastReceivedBid);
        }

        // Offer the proposed bid
        System.out.format("I'm going to offer the bid that I generated, which has utility [%f]%n", additiveUtilitySpace.getUtility(proposedBid));
        return new Offer(getPartyId(), proposedBid);
    }
    /**
     * Compute the minimum utility that is acceptable at the current moment
     * @return double indicating the min utility
     */
    private double getMinAcceptableUtility()
    {
        double timeLeft = 1 - getTimeLine().getTime();
        // At the beginning of the negotiation the minimum utility will be 0.9,
        double minUtility = Math.log10(timeLeft) / this.getConcedingFactor() + 0.9D;

        return Math.max(minUtility, this.RESERVATION_VALUE);
    }

    /**
     * Compute the conceding factor. If one of the opponents are hard headed concede faster
     * @return double indicating the conceding factor
     */
    private double getConcedingFactor()
    {
        Double max = 0.0;
        Double current = 0.0;
        for (AgentID id : this.opponentsMap.keySet()){
            // Get how hard headed this agent is considering last 40 rounds
            current = this.opponentsMap.get(id).hardHeaded(40);
            if (current != null && current > max){
                max = current;
            }
        }

        if (this.timeline.getTime() < 0.96D){
            return 13;
        }
        // Check whether at least one agent is hard headed
        // If so we should concede faster
        if (max > 0.6){
            return 7.0;
        }
        else{
            return 10.0;
        }
    }
    /**
     * Determines whether we should accept the latest opponent's bid
     * @param proposedBid The bid that we're going to offer
     * @return boolean indicating whether we should accept the latest bid
     */
    private boolean isAcceptable(Bid proposedBid) {
        // Check if the utility of the latest received offer is higher than the utility
        // of the bid we are going to offer
        boolean aNext = getUtility(this.lastReceivedBid) >= getUtility(proposedBid);
        // Get the minimum acceptable utility
        double minUtility = this.getMinAcceptableUtility();

        System.out.format("Min utility: [%f]%n", minUtility);

        // We accept the latest offer if it has a greater utility than the one we are proposing,
        // or if its utility is higher than our minUtility
        return (aNext || getUtility(this.lastReceivedBid) > minUtility);
    }

    /**
     * Determines if we're in the time in which we should just keep
     * offering the max utility bid
     * @return boolean indicating whether we should offer the maximum utility bid
     */
    private boolean isMaxUtilityOfferTime() {
        return getTimeLine().getTime() < this.TIME_OFFERING_MAX_UTILITY_BID;
    }

    /**
     * Reception of offers made by other parties.
     * @param sender The party that did the action. Can be null.
     * @param action The action that party did.
     */
    @Override
    public void receiveMessage(AgentID sender, Action action) {
        super.receiveMessage(sender, action);

        // If we're receiving an offer
        if (sender != null && action instanceof Offer) {
            // Store the bid as the latest received bid
            this.lastReceivedBid = ((Offer) action).getBid();

            // Store the bid in the opponent's history
            if (opponentsMap.containsKey(sender)) {
                opponentsMap.get(sender).addBid(this.lastReceivedBid);
            } else {
                // If it's the first time we see this opponent, create a new
                // entry in the opponent map
                try {
                    Opponent newOpponent = new Opponent(generateRandomBid());
                    newOpponent.addBid(this.lastReceivedBid);
                    opponentsMap.put(sender, newOpponent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Generates a random bid that has an higher utility than
     * our own reservation value, by only searching in the space of acceptable utility
     * @return the random bid with utility higher than the reservation value
     */
    private Bid generateAcceptableRandomBid(double minimum_acceptable_utility) {
        Bid bid;
        do {
            // Generate random double in range 0:1
            double randomNumber = this.randomGenerator.nextDouble();
            // Map randomNumber in range (minimum acceptable utility : 1)
            double utility = minimum_acceptable_utility + randomNumber * (1.0 - minimum_acceptable_utility);
            // Get a bid closest to $utility
            bid = SOS.getBidNearUtility(utility).getBid();
        } while (getUtility(bid) <= minimum_acceptable_utility);
        return bid;
    }
    /**
     * Generates a new bid basing on our own utility and the estimated utility
     * of the opponents, through frequency analysis
     * @return the generated bid, which has always a utility higher than our reservation value
     */
    private Bid generateBid() {
        double nashProduct;
        Bid randomBid;

        double acceptableUtility = this.getMinAcceptableUtility();
        Bid bestBid = generateAcceptableRandomBid(acceptableUtility);
        double bestNashProduct = -1;

        // Every 20 rounds, recompute the Nash product of the best saved bids of the opponents
        // This is done to better approximate them, by taking new proposed bid into account
        // in the opponent modeling
        if (this.bestGeneratedBids.size() >= 100 && this.turn % 20 == 0){
            this.recomputeUtilities();
        }

        // Generate 100 times random (valid) bids and see which one has a better average utility
        // for the opponents
        for (int i = 0; i < 100; i++) {
            // Generate a valid random bid, which is above our minimum acceptable utility
            randomBid = generateAcceptableRandomBid(acceptableUtility);

            nashProduct = this.getNashProduct(randomBid);
            // Only save the best best bid found
            if (nashProduct > bestNashProduct) {
                bestBid = randomBid;
                bestNashProduct = nashProduct;
            }
        }
        // Save the best bid if the bestGenenratedBits list is not full
        if (this.bestGeneratedBids.size() < maxAmountSavedBits){
            this.bestGeneratedBids.add(new BidDetails(bestBid, bestNashProduct));
            // If the list gets full sort it
            if (this.bestGeneratedBids.size() == this.maxAmountSavedBits){
                this.sortBestBids();
            }
        }
        else {
            // Get the worst bid saved in $bestGeneratedBits
            double worstBidsUtility = this.bestGeneratedBids.get(this.maxAmountSavedBits -1).getMyUndiscountedUtil();
            // If $bestBid is better than save it and remove the worst bid
            if (bestNashProduct > worstBidsUtility){
                this.bestGeneratedBids.remove(0);
                this.bestGeneratedBids.add(new BidDetails(bestBid, bestNashProduct));
                this.sortBestBids();
            }
        }

        // When enough bids are saved, offer one of the best bids
        if (this.bestGeneratedBids.size() >= this.maxAmountSavedBits){
            // Get index of one of the 5 best saved bids
            // this.bestGeneratdBids is sorted in ascending order with the utility of the opponents as key
            int index =  this.maxAmountSavedBits - randomGenerator.nextInt(5) - 1;
            bestBid = this.bestGeneratedBids.get(index).getBid();
        }
        return bestBid;
    }

    /**
     * Sort this.bestGeneratedBids by its utilities
     */
    private void sortBestBids(){
        Collections.sort(this.bestGeneratedBids, new Comparator<BidDetails>() {
            @Override
            public int compare(BidDetails bid1, BidDetails bid2) {
                if (bid1.getMyUndiscountedUtil() < bid2.getMyUndiscountedUtil()) return -1;
                else if (bid1.getMyUndiscountedUtil() == bid2.getMyUndiscountedUtil()) return 0;
                else return 1;
            }
        });
    }

    /**
     * Recompute the nash products of this.bestGeneratedBids.
     */
    private void recomputeUtilities()
    {
        for (BidDetails b: this.bestGeneratedBids){
            b.setMyUndiscountedUtil(this.getNashProduct(b.getBid()));
        }
    }

    private double getNashProduct(Bid bid)
    {
        double nash = this.getUtility(bid);
        for (AgentID agent : this.opponentsMap.keySet()) {
            nash *= this.opponentsMap.get(agent).getUtility(bid);
        }
        return nash;
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
        System.out.println("totalnum:"+totalnum);
        double points = 0;
        for (Bid b : r.getBidOrder()) {

            List<Issue> issues = b.getIssues();
            int m=issues.size();
//            List<Issue> issuesList = bid.getIssues();
            //           System.out.println("this is Bid "+b.getValue(1)+" "+b.getValue(2)+" "+b.getValue(3));
            for (Issue issue : issues) {
                //int n=issue
                System.out.println(issue.getName() + ": " + b.getValue(issue.getNumber()));
                System.out.println(issue.getNumber()+"*****************************");
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
     * Description of the agent
     */
    @Override
    public String getDescription() {
        return description;
    }















//    @Override
//    public void init(NegotiationInfo info) {
//        super.init(info);
//        //初始化时,从获取Bid流，从小到大排序
//        rankedBids = userModel.getBidRanking().getBidOrder();
//        for(Bid bid:rankedBids){
//            System.out.println("打印出第"+count+"个bid"+bid);
//            count++;
//        }
//
//        }




//        System.out.println("number of ranking: " + iterator);

//        factory.estimateUsingBidRanks(userModel.getBidRanking());
//        AbstractUtilitySpace utilitySpace = factory.getUtilitySpace();
//        AdditiveUtilitySpace additiveUtilitySpace = (AdditiveUtilitySpace) utilitySpace;
//        List<Issue> issues = additiveUtilitySpace.getDomain().getIssues();
//
//        for (Issue issue : issues) {
//            int issueNumber = issue.getNumber();
//            System.out.println(">> " + issue.getName() + " weight: " + additiveUtilitySpace.getWeight(issueNumber));
//        }



//    @Override
//    public Action chooseAction(List<Class<? extends Action>> list) {
//        // According to Stacked Alternating Offers Protocol list includes
//        // Accept, Offer and EndNegotiation actions only.
//        double time = getTimeLine().getTime(); // Gets the time, running from t = 0 (start) to t = 1 (deadline).
//        // The time is normalized, so agents need not be
//        // concerned with the actual internal clock.
//
//        // First half of the negotiation offering the max utility (the best agreement possible) for Example Agent
//        if (time < 0.5) {
//            return new Offer(this.getPartyId(), this.getMaxUtilityBid());
//        } else {
//
//            // Accepts the bid on the table in this phase,
//            // if the utility of the bid is higher than Example Agent's last bid.
//            if (lastReceivedOffer != null
//                    && myLastOffer != null
//                    && this.utilitySpace.getUtility(lastReceivedOffer) > this.utilitySpace.getUtility(myLastOffer) &&
//                    (this.utilitySpace.getUtility(lastReceivedOffer)>0.7)) {
//                System.out.println("system want to make agreement at utility:\n");
//                System.out.println(this.utilitySpace.getUtility(lastReceivedOffer));
//                return new Accept(this.getPartyId(), lastReceivedOffer);
//            } else {
//                // Offering a random bid
//                //myLastOffer = generateRandomBid();
//
//                // Offering a random bid with utility >0.7
//                myLastOffer = generateRandomBidWithUtility(0.7); //offer with utility lager than 0.7
//                return new Offer(this.getPartyId(), myLastOffer);
//            }
//        }
//    }

//    /**
//     * This method is called to inform the party that another NegotiationParty chose an Action.
//     * @param sender
//     * @param act
//     */
//    @Override
//    public void receiveMessage(AgentID sender, Action act) {
//        super.receiveMessage(sender, act);
//
//        if (act instanceof Offer) { // sender is making an offer
//            Offer offer = (Offer) act;
//
//            // storing last received offer
//            lastReceivedOffer = offer.getBid();
//        }
//    }
//
//    /**
//     * A human-readable description for this party.
//     * @return
//     */
//    @Override
//    public String getDescription() {
//        return description;
//    }
//
//    private Bid getMaxUtilityBid() {
//        try {
//            return this.utilitySpace.getMaxUtilityBid();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return null;
//    }
//
//    /**
//     * Beware that if utilityThreshold is really high (e.g. 0.9),
//     * there might not be a possible offer in given preference profile.
//     * To be safe, compare your utilityThreshold with MaxUtility
//     *
//     * @param utilityThreshold
//     * @return
//     */
//    public Bid generateRandomBidWithUtility(double utilityThreshold) {
//        Bid randomBid;
//        double utility;
//        do {
//            randomBid = generateRandomBid();
//            try {
//                utility = utilitySpace.getUtility(randomBid);
//            } catch (Exception e)
//            {
//                utility = 0.0;
//            }
//        }
//        while (utility < utilityThreshold);
//        return randomBid;
//    }
//
////    @Override
////    public AbstractUtilitySpace estimateUtilitySpace() {
////        //Old function that not work really well
////        Domain domain = getDomain();
////        AdditiveUtilitySpaceFactory factory = new AdditiveUtilitySpaceFactory(domain);
////        BidRanking r = userModel.getBidRanking();
////
////        //copy codes from AdditiveUtilitySpaceFactory.estimateUsingBidRanks
////        double points = 0;
////        for (Bid b : r.getBidOrder())
////        {
////            List<Issue> issues = b.getIssues();
////            for (Issue i : issues)
////            {
////                int no = i.getNumber();
////                ValueDiscrete v = (ValueDiscrete) b.getValue(no);
////                double oldUtil = factory.getUtility(i, v);
//////                System.out.println("old utility of "+i.getName()+i.getNumber()+": "+oldUtil);
////                factory.setUtility(i, v, oldUtil + points);
////            }
////            points += 1;
////        }
////        factory.normalizeWeightsByMaxValues();
////
////        return factory.getUtilitySpace();
//////        return new AdditiveUtilitySpaceFactory(getDomain()).getUtilitySpace();
////    }
//
//    public AbstractUtilitySpace estimateUtilitySpace_a10() {
//        //定义一个double型的二维数组，用来存放K值
//        //Mun[m][n],m是issue的个数，n是value的个数
//        double Num[][];
//        //获取进来的domain
//        Domain domain = getDomain();
//        //根据domain生成factory
//        AdditiveUtilitySpaceFactory factory = new AdditiveUtilitySpaceFactory(domain);
//        //usermodel生成一个BidRanking，里面都是一个个bid,放到r里
//        BidRanking r = userModel.getBidRanking();
//        //计算一共有多少个bid,赋值给totalnum
//        int totalnum = r.getBidOrder().size();
//        System.out.println("totalnum:"+totalnum);
//        double points = 0;
//        for (Bid b : r.getBidOrder()) {
//
//            List<Issue> issues = b.getIssues();
//            int m=issues.size();
////            List<Issue> issuesList = bid.getIssues();
// //           System.out.println("this is Bid "+b.getValue(1)+" "+b.getValue(2)+" "+b.getValue(3));
//            for (Issue issue : issues) {
//                //int n=issue
//                System.out.println(issue.getName() + ": " + b.getValue(issue.getNumber()));
//                System.out.println(issue.getNumber()+"*****************************");
//            }
//
//            /////////////////////////////////////////
//            for (Issue i : issues) {
//                int no = i.getNumber();
//                ValueDiscrete v = (ValueDiscrete) b.getValue(no);
//                double oldUtil = factory.getUtility(i, v);
////                System.out.println("old utility of "+i.getName()+i.getNumber()+": "+oldUtil);
//                factory.setUtility(i, v, oldUtil + points);
//            }
//            points += 1;
//        }
//        factory.normalizeWeightsByMaxValues();
//
//        return factory.getUtilitySpace();
////        return new AdditiveUtilitySpaceFactory(getDomain()).getUtilitySpace();
//
//    }

}

