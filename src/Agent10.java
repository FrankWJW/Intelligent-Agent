import agents.org.apache.commons.lang.ArrayUtils;
import genius.core.AgentID;
import genius.core.Bid;
import genius.core.Domain;
import genius.core.actions.Accept;
import genius.core.actions.Action;
import genius.core.actions.Offer;
import genius.core.issue.*;
import genius.core.parties.AbstractNegotiationParty;
import genius.core.parties.NegotiationInfo;
import genius.core.uncertainty.AdditiveUtilitySpaceFactory;
import genius.core.uncertainty.BidRanking;
import genius.core.utility.AbstractUtilitySpace;
import genius.core.utility.AdditiveUtilitySpace;
import genius.core.utility.Evaluator;
import genius.core.utility.EvaluatorDiscrete;

import java.util.*;

/**
 * ExampleAgent returns the bid that maximizes its own utility for half of the negotiation session.
 * In the second half, it offers a random bid. It only accepts the bid on the table in this phase,
 * if the utility of the bid is higher than Example Agent's last bid.
 */
public class Agent10 extends AbstractNegotiationParty {
    private final String description = "Agent10";

    // ************************************************************************************
    // *************** Private Class used to estimate self model *******************
    // ************************************************************************************
    private class agentModel {
        private double Issues [][];
        private double Weights [];
        public int frequency [][];
        private int N_issues;
        private int N_values;
        private int maximum_freqs [];
        private int weight_ranking[];
        private int value_ranking[];

        //Initial opponentModel
        public agentModel(int num_issues, int num_values, int [][] freq) {
            Issues = new double [num_issues][num_values]; //Initial opponentModel
            Weights = new double [num_issues]; //每一个issues一个weight
            frequency = freq; //2D array
            N_issues = num_issues; //the number of issues
            N_values = num_values; //the number of values for each issues
            maximum_freqs = new int[N_issues];
            weight_ranking = new int [N_issues];
            value_ranking = new int [N_values];
        }

        public void updateModel() {
            //System.out.println("");
            int j = 0;
            int k = 0;
            int i = 0;
            for(i = 0; i < N_issues; i++) {
                maximum_freqs[i] = Collections.max(Arrays.asList(ArrayUtils.toObject(frequency[i])));
                if(maximum_freqs[i] == 0) {
                    maximum_freqs[i] = 1;
                }

                //根据得到的频率排列issues的顺序，weight ranking[]
                weight_ranking[i] = N_issues-i;
                j = i - 1; //从第二个开始，对每一个和前一个的频率进行比较
                while(j != -1) {
                    if(maximum_freqs[i] > maximum_freqs[j]) {
                        weight_ranking[i] = Math.max(weight_ranking[j], weight_ranking[i]); //频率大的排在前面
                        weight_ranking[j]--; //频率小的排在后边
                    }
                    j--; //为了遍历所有frequency值，跳出while循环
                }
                //System.out.format("\nIndex %d, Maximum Frequency = %d\n", i, maximum_freqs[i]);

                //排好issues以后对地i个issues里的value进行排序，同样根据频率
                for(j = 0; j < N_values; j++) {
                    value_ranking[j] = N_values-j;
                    k = j - 1;
                    while(k != -1) {
                        if(frequency[i][j] > frequency[i][k]) {
                            value_ranking[j]++; //频率大的排在前面
                            value_ranking[k]--; //频率大的排在前面
                        }
                        if(frequency[i][j] == frequency[i][k]) {
                            value_ranking[k]--;
                            value_ranking[j] = Math.min(value_ranking[k], value_ranking[j]);
                        }
                        k--; //为了遍历所有frequency值，跳出while循环
                    }
                }

                //issues[i][j]是第i个issues里有j个value
                //对values赋值
                for(j = 0; j < N_values; j++) {
                    Issues[i][j] = value_ranking[j]*1.0/N_values;
                }
            }

            //每一个issues对应一个weight值
            for(i = 0; i < N_issues; i++) {
                Weights[i] = 2.0*weight_ranking[i]/(N_issues*(N_issues+1.0));
            }
        }

        //Design a utility for comparing
        public double predictUtility(Bid bid) {
            double U = 0.0;
            for(Issue issue: bid.getIssues()) {
                U = U +
                        Issues[issue.getNumber()-1][((IssueDiscrete) issue).getValueIndex((ValueDiscrete)(bid.getValue(issue.getNumber())))] *
                                Weights[issue.getNumber()-1];
            }
            //System.out.format("\n\nPredicted utility = %f", U);
            return U;
        }

        //Get the maximum value for 第issue_index个issue
        public int getIssueMaxValueIndex(int issue_index) {
            int max_index = 0;
            for(int i = 0; i < N_values; i++) {
                if(Issues[issue_index][max_index] < Issues[issue_index][i]) {
                    max_index = i;
                }
            }
            return max_index; //找出第几个是最大的
        }

        //get each value for every issue
        public double getIssueValue(int issue_index, int value_index) {
            return Issues[issue_index][value_index];
        }

        //get each value for every issue
        public double getWeight(int issue_index) {
            return Weights[issue_index];
        }
    };

    // **************************************************************************************************
    // **************************************************************************************************
    private agentModel selfModel;
    private agentModel opponetModel;

    private Bid lastReceivedOffer; // offer on the table
    private Bid myLastOffer;
    private Bid maxUtilityOffer;
    private Bid minUtilityOffer;

    //parameters used to compute the target utility
    private double Umax;
    private double Umin;
    private double k;
    private double b;

    //for collecting opponent data
    private int hashcode_a;
    private int hashcode_b;
    private int number_of_issues; // introduced this to aid in simulated annealing process ..

    private 	int max_num_of_values;

    Bid curr_bid ;
    private java.util.List<Issue> domain_issues;
    private java.util.List<ValueDiscrete> values;
    private EvaluatorDiscrete evaluator;

    // Two dimensional array, number of issues times number of max number of values ...
    int freq_a [][];
    int freq_b [][];
    double max_weight = 0; //让max最小，min最大以便更新
    double min_weight = 1;

    AdditiveUtilitySpace additiveUtilitySpace_i;
    int max_weight_number =1 ;
    double panic;
    @Override
    public void init(NegotiationInfo info) {
        super.init(info);
        AbstractUtilitySpace utilitySpace = estimateUtilitySpace_a10();
        AdditiveUtilitySpace additiveUtilitySpace = (AdditiveUtilitySpace) utilitySpace;
        List< Issue > issues = additiveUtilitySpace.getDomain().getIssues();

        for (Issue issue : issues) {
            int issueNumber = issue.getNumber();
            System.out.println(">> " + issue.getName() + " weight: " + additiveUtilitySpace.getWeight(issueNumber));

            // Assuming that issues are discrete only
            IssueDiscrete issueDiscrete = (IssueDiscrete) issue;
            EvaluatorDiscrete evaluatorDiscrete = (EvaluatorDiscrete) additiveUtilitySpace.getEvaluator(issueNumber);

            for (ValueDiscrete valueDiscrete : issueDiscrete.getValues()) {
                System.out.println(valueDiscrete.getValue());
//                System.out.println("Evaluation(getValue): " + evaluatorDiscrete.getValue(valueDiscrete));
                try {
//                    System.out.println("Evaluation(getEvaluation): " + evaluatorDiscrete.getEvaluation(valueDiscrete));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        List<Bid> bids = userModel.getBidRanking().getBidOrder();
        int numberOfBids=bids.size();
        int numberOfIssue=bids.get(0).getIssues().size();
       // System.out.println("多少个issue："+numberOfIssue);
        Value[][] bid_Array=new Value[numberOfBids][numberOfIssue];
       // System.out.println("total Bids: "+numberOfBids);
        for (int i = 0; i < numberOfBids; i++) {
            for (int j = 0; j < numberOfIssue; j++) {
                bid_Array[i][j]=bids.get(i).getValue(j+1);
               // System.out.println("i,j"+bid_Array[i][j]);
            }
        }
        //int[][] freq_table=new int[numberOfIssue][];
        Value flag[]=new Value[numberOfBids];
        int type[]=new int[numberOfIssue];
        Map<Value,Integer> map_issuemax = new HashMap<Value, Integer>();

        for (int j = 0; j < numberOfIssue; j++) {
            for (int i = 0; i < numberOfBids; i++) {
                //记录下每列第i个value
                flag[i] = bid_Array[i][j];
            }
            Map<Value,Integer> map = new HashMap<Value, Integer>();
            for (Value val:flag){
                Integer num=map.get(val);
                //key是唯一的
                map.put(val,num==null?1:num+1);
            }
            for (Map.Entry<Value, Integer> entry : map.entrySet()) {
                System.out.println("单词 " + entry.getKey() + " 出现次数 :" + entry.getValue());
                type[j]++;
            }
            List<Map.Entry<Value, Integer>> infoIds = new ArrayList<Map.Entry<Value, Integer>>(map.entrySet());
            Collections.sort(infoIds, new Comparator<Map.Entry<Value, Integer>>() {
                public int compare(Map.Entry<Value, Integer> o1,
                                   Map.Entry<Value, Integer> o2) {
                    return (o1.getValue()).toString().compareTo(o2.getValue().toString());
                }
            });
            //这里的max指的是一个issue中坚持次数最多的那个value
            //value坚持越多，说明该issue越重要
            System.out.print("max:"+infoIds.get(infoIds.size()-1)+"\n");

            map_issuemax.put(infoIds.get(infoIds.size()-1).getKey(),infoIds.get(infoIds.size()-1).getValue());

            List<Map.Entry<Value, Integer>> infoIds_issuemax = new ArrayList<Map.Entry<Value, Integer>>(map_issuemax.entrySet());
            Collections.sort(infoIds_issuemax, new Comparator<Map.Entry<Value, Integer>>() {
                public int compare(Map.Entry<Value, Integer> o1,
                                   Map.Entry<Value, Integer> o2) {
                    return (o1.getValue()).toString().compareTo(o2.getValue().toString());
                }
            });
                        //输出最大utility的一行
            for (int i = 0; i < infoIds_issuemax.size(); i++) {
                String id = infoIds_issuemax.get(i).toString();
                System.out.print(id + "  ");
            }
//            //这里的i指的是一个issue有多少个value
//            for (int i = 0; i < infoIds.size(); i++) {
//                String id = infoIds.get(i).toString();
//                //System.out.print(id + "  ");
//            }
            System.out.println("这个issue有"+type[j]+"个value");
           // item[type[j]]
        }

//        Value record_col[];
//        int count=0;
//        for (int j = 0; j < numberOfIssue; j++) {
//            for (int i = 0; i < numberOfBids; i++) {
//                //记录下每列第一个value
//                record_col[i]=bid_Array[i][j];
//                if(bid_Array[i+1][j]==record_col&&(i+1)<numberOfBids){
//                    count++;
//                }
//
//            }
//        }
        for (Bid bid : bids) {

            List<Issue> issuesList = bid.getIssues();
            //int numberOfIssue=issuesList.size();
          //  double[][] bid_Array=new double[numberOfBids][numberOfIssue];
           // System.out.println("this is Bid "+bid.getValue(1)+" "+bid.getValue(2)+" "+bid.getValue(3));
           // System.out.println("一共有"+numberOfIssue+"个issue");
            for (Issue issue : issuesList) {
                System.out.println("第几个issue："+issue.getNumber() + "这个issue对应的value:" + bid.getValue(issue.getNumber()));

                //System.out.println(+"*****************************");
            }
        }

        for (int i = 0; i < numberOfBids; i++) {

        }

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

    }
    /**
     * 
     * 功能://统计数组中重复元素的个数
     * @param array
     * void
     */

    /**
     * When this function is called, it is expected that the Party chooses one of the actions from the possible
     * action list and returns an instance of the chosen action.
     *
     * @param list
     * @return
     */
    @Override
    public Action chooseAction(List<Class<? extends Action>> list) {
        // According to Stacked Alternating Offers Protocol list includes
        // Accept, Offer and EndNegotiation actions only.
        double time = getTimeLine().getTime(); // Gets the time, running from t = 0 (start) to t = 1 (deadline).
        // The time is normalized, so agents need not be
        // concerned with the actual internal clock.

        // First half of the negotiation offering the max utility (the best agreement possible) for Example Agent
        if (time < 0.5) {
            return new Offer(this.getPartyId(), this.getMaxUtilityBid());
        } else {

            // Accepts the bid on the table in this phase,
            // if the utility of the bid is higher than Example Agent's last bid.
            if (lastReceivedOffer != null
                    && myLastOffer != null
                    && this.utilitySpace.getUtility(lastReceivedOffer) > this.utilitySpace.getUtility(myLastOffer) &&
                    (this.utilitySpace.getUtility(lastReceivedOffer)>0.7)) {
                System.out.println("system want to make agreement at utility:\n");
                System.out.println(this.utilitySpace.getUtility(lastReceivedOffer));
                return new Accept(this.getPartyId(), lastReceivedOffer);
            } else {
                // Offering a random bid
                //myLastOffer = generateRandomBid();

                // Offering a random bid with utility >0.7
                myLastOffer = generateRandomBidWithUtility(0.7); //offer with utility lager than 0.7
                return new Offer(this.getPartyId(), myLastOffer);
            }
        }
    }

    /**
     * This method is called to inform the party that another NegotiationParty chose an Action.
     * @param sender
     * @param act
     */
    @Override
    public void receiveMessage(AgentID sender, Action act) {
        super.receiveMessage(sender, act);

        if (act instanceof Offer) { // sender is making an offer
            Offer offer = (Offer) act;

            // storing last received offer
            lastReceivedOffer = offer.getBid();
        }
    }

    /**
     * A human-readable description for this party.
     * @return
     */
    @Override
    public String getDescription() {
        return description;
    }

    private Bid getMaxUtilityBid() {
        try {
            return this.utilitySpace.getMaxUtilityBid();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Beware that if utilityThreshold is really high (e.g. 0.9),
     * there might not be a possible offer in given preference profile.
     * To be safe, compare your utilityThreshold with MaxUtility
     *
     * @param utilityThreshold
     * @return
     */
    public Bid generateRandomBidWithUtility(double utilityThreshold) {
        Bid randomBid;
        double utility;
        do {
            randomBid = generateRandomBid();
            try {
                utility = utilitySpace.getUtility(randomBid);
            } catch (Exception e)
            {
                utility = 0.0;
            }
        }
        while (utility < utilityThreshold);
        return randomBid;
    }

//    @Override
//    public AbstractUtilitySpace estimateUtilitySpace() {
//        //Old function that not work really well
//        Domain domain = getDomain();
//        AdditiveUtilitySpaceFactory factory = new AdditiveUtilitySpaceFactory(domain);
//        BidRanking r = userModel.getBidRanking();
//
//        //copy codes from AdditiveUtilitySpaceFactory.estimateUsingBidRanks
//        double points = 0;
//        for (Bid b : r.getBidOrder())
//        {
//            List<Issue> issues = b.getIssues();
//            for (Issue i : issues)
//            {
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
//    }

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

}

