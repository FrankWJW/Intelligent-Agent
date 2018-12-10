import java.util.*;

import agents.org.apache.commons.lang.ArrayUtils;
import genius.core.AgentID;
import genius.core.Bid;
import genius.core.Domain;
import genius.core.actions.Accept;
import genius.core.actions.Action;
import genius.core.actions.Offer;
import genius.core.issue.Issue;
import genius.core.issue.IssueDiscrete;
import genius.core.issue.Value;
import genius.core.issue.ValueDiscrete;
import genius.core.parties.AbstractNegotiationParty;
import genius.core.parties.NegotiationInfo;
import genius.core.uncertainty.AdditiveUtilitySpaceFactory;
import genius.core.uncertainty.BidRanking;
import genius.core.utility.AbstractUtilitySpace;
import genius.core.utility.AdditiveUtilitySpace;
import genius.core.utility.EvaluatorDiscrete;

public class Agent26 extends AbstractNegotiationParty {

    private final String description = "Agent26";

    // ************************************************************************************
    // *************** Private Class used to predict the opponent model *******************
    // ************************************************************************************

    //Design an opponent model
    private class OpponentModel {
        private double Issues [][];
        private double Weights [];
        public int frequency [][];
        private int N_issues;
        private int N_values;
        private int maximum_freqs [];
        private int weight_ranking[];
        private int value_ranking[];

        //Initial opponentModel
        public OpponentModel(int num_issues, int num_values, int [][] freq) {
            Issues = new double [num_issues][num_values]; //Initial opponentModel
            Weights = new double [num_issues]; //每一个issues一个weight
            frequency = freq; //2D array
            N_issues = num_issues; //the number of issues
            N_values = num_values; //the number of values for each issues
            maximum_freqs = new int[N_issues];
            weight_ranking = new int [N_issues];
            value_ranking = new int [N_values];
        }

        //update the opponent model
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

    private OpponentModel opponentA;
    private OpponentModel opponentB;

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

    //是否可以直接这样定义
    //*********************************************
    private java.util.List<Issue> domain_issues;
    private java.util.List<ValueDiscrete> values;
    private EvaluatorDiscrete evaluator;
    //*********************************************

    AbstractUtilitySpace utilitySpace;
    AdditiveUtilitySpace additiveUtilitySpace;

    // Two dimensional array, number of issues times number of max number of values ...
    int freq_a [][];
    //int freq_b [][];
    double max_weight = 0; //让max最小，min最大以便更新
    double min_weight = 1;

    AdditiveUtilitySpace additiveUtilitySpace_i; //**********************
    int max_weight_number =1 ;
    double panic;

    @Override
    public void init(NegotiationInfo info) {
        super.init(info);

        utilitySpace = estimateUtilitySpace_a10();
        additiveUtilitySpace = (AdditiveUtilitySpace) utilitySpace;

        panic = 0.99;

        // Initialize the Agent's histories and the hashcodes to 0
        hashcode_a = 0;
        hashcode_b = 0;
        curr_bid = this.getMaxUtilityBid(); //user defined function below

        // Get the Max and Min Utility Offers
        maxUtilityOffer = this.getMaxUtilityBid();
        minUtilityOffer = this.getMinUtilityBid();

        // Initialize the parameters used to compute the target utility
        Umax = 1.0;
        Umin = (utilitySpace.getUtility(minUtilityOffer)+Umax)/2.0;
        k = 0.2;
        b = 1.5;

        // Get the Domain Issues 需要改正
        domain_issues = utilitySpace.getDomain().getIssues();

        number_of_issues = domain_issues.size(); //取得issues的个数
        double curr_weight;

        // Cast the Utility Space into an Additive Utility Space to have access to its methods ***
        additiveUtilitySpace_i = (AdditiveUtilitySpace)utilitySpace;

        max_num_of_values = 0;

        for(Issue lIssue : domain_issues) {
            IssueDiscrete lIssueDiscrete = (IssueDiscrete) lIssue;
            evaluator = (EvaluatorDiscrete) (((AdditiveUtilitySpace)utilitySpace).getEvaluator(lIssue.getNumber()));
            values = lIssueDiscrete.getValues();
            curr_weight = (additiveUtilitySpace_i.getWeight(lIssue.getNumber())); //取得每个issue的weight ***

            //更新自己的最大最小weight
            if(curr_weight < min_weight) {
                min_weight = curr_weight;
            }
            if(curr_weight > max_weight){
                max_weight_number = lIssue.getNumber();
                max_weight = curr_weight;
            }
            if(values.size() > max_num_of_values){
                max_num_of_values = values.size();
            }
        }

        //initial the array to store the frequency for each value
        freq_a = new int [number_of_issues][max_num_of_values];
        //freq_b = new int [number_of_issues][max_num_of_values];

        //Initial opponents
        opponentA = new OpponentModel(number_of_issues, max_num_of_values, freq_a);
        //opponentB = new OpponentModel(number_of_issues, max_num_of_values, freq_b);

        //Lets initialize the array
        for(int i = 0 ; i < number_of_issues ; i ++){
            for(int j = 0 ; j < max_num_of_values ; j++){
                freq_a [i][j] = 0;
                //freq_b [i][j] = 0;
            }
        }

    }


    public Action chooseAction(List<Class<? extends Action>> list) {

        //System.out.println(domain_issues.get(0).getType());
        //System.out.println(additiveUtilitySpace_i.getWeight(1));

        // According to Stacked Alternating Offers Protocol list includes
        // Accept, Offer and EndNegotiation actions only.
        if(lastReceivedOffer == null) {
            //Lets start with our maximum because we are bad boys
            myLastOffer = maxUtilityOffer;
            return new Offer(this.getPartyId(), myLastOffer); //产生自己的offer：ID -- Offer
        }

        //Every now and then just offer our maximum, depending on time..



        // Compute the target utility
        double util;
        //*******************************
        util = this.getTargetUtility(k, b); //k,b是计算时的系数，根据之前的最大最小utility以及时间来计算目标utility


        //*If the offer is good enough for us or we've reached panic, then accept the offer...
        if(acceptOrOffer(lastReceivedOffer, util) ) {
            return new Accept(this.getPartyId(), lastReceivedOffer);
        }
        else if (getTimeLine().getTime() > panic ){ //****************
            return new Accept(this.getPartyId(), lastReceivedOffer);
        }

        else { //否则不接受，继续对对手预测（建模）并且提供新的offer
            opponentA.updateModel();
            //opponentB.updateModel();
            myLastOffer = generateGoodBid();
            //70% of the time we generate a "good enough bid", this is to collect enough data to predict the nash offer ...
            if(getTimeLine().getTime() < 0.7){ //谈判一开始一直提供对自己有利的
                myLastOffer = generateGoodBid();
            }
            else if (model_valid(0) && model_valid (1)){ //issue最大最小值差别要大
                myLastOffer = generateNashBid(); //提供一个比较nash的offer
                if(utilitySpace.getUtility(myLastOffer) < Umin){
                    myLastOffer = generateGoodBid(); //nash低于底线时产生对自己有利的
                }
            }
            else {
                System.out.println("ASSHOLE DETECTED");
                myLastOffer = generateGoodBid(); //否则一直提供对自己有利的offer
            }

            return new Offer (this.getPartyId(), myLastOffer);
        }
    }


    public void receiveMessage(AgentID sender, Action act) {

        super.receiveMessage(sender, act);

        if (act instanceof Accept && lastReceivedOffer != null) {
            if(hashcode_a == 0) {
                // Agent A is the first agent to make an offer
                hashcode_a = sender.hashCode();
            }
            else if (hashcode_b == 0) {
                // Agent B is the second agent to make an offer
                hashcode_b = sender.hashCode();
            }
            if(sender.hashCode() == hashcode_a){
                update_freq(lastReceivedOffer , 0);
            }
            else if(sender.hashCode() == hashcode_b){
                update_freq(lastReceivedOffer , 1);
            }
        }


        if (act instanceof Offer) {
            if(hashcode_a == 0) {
                // Agent A is the first agent to make an offer
                hashcode_a = sender.hashCode();
            }
            else if (hashcode_b == 0) {
                // Agent B is the second agent to make an offer
                hashcode_b = sender.hashCode();
            }
            Offer offer = (Offer) act;
            lastReceivedOffer = offer.getBid();
            if(sender.hashCode() == hashcode_a){
                update_freq(lastReceivedOffer , 0);
            }
            else if(sender.hashCode() == hashcode_b){
                update_freq(lastReceivedOffer , 1);
            }
        }
    }


    // So if there is not a significant difference in each issue max and min value, then we return true...
    private boolean model_valid(int agent){
        int max_value, min_value;
        double  difference;
        if(agent == 0){
            for(int i = 0 ; i < domain_issues.size() ; i ++){
                max_value  = 0 ;
                min_value = 20000000;
                for(int j = 0 ; j < max_num_of_values ; j++){
                    if(freq_a[i][j] > max_value){
                        max_value = freq_a[i][j];
                    }
                    if(freq_a[i][j] < min_value){
                        min_value = freq_a[i][j];
                    }
                }
                difference = ((max_value - min_value) * 100 )/ max_value;
                //Less than 20% of difference, then something is really wrong on the model ...
                if(difference < 20){
                    return false;}

            }
            return true;
        }
//        else{
//            for(int i = 0 ; i < domain_issues.size() ; i ++){
//                max_value  = 0 ;
//                min_value = 20000000;
//                for(int j = 0 ; j < max_num_of_values ; j++){
//                    if(freq_b[i][j] > max_value){
//                        max_value = freq_b[i][j];
//                    }
//                    if(freq_b[i][j] < min_value){
//                        min_value = freq_b[i][j];
//                    }
//                }
//                difference = ((max_value - min_value) * 100 )/ max_value;
//                //Less than 20% of difference, then something is really wrong on the model ...
//                if(difference < 20){
//                    return false;}
//
//            }
//        }
        return true;
    }

    //Update frequency array used to predict opponet model ...
    public void update_freq (Bid curr_bid_freq , int agent_num){

        java.util.List<Issue> bid_issues;
        java.util.HashMap<java.lang.Integer,Value> 	bid_values;
        java.util.List<ValueDiscrete> issue_values;
        IssueDiscrete lIssueDiscrete ;
        ValueDiscrete lValueDiscrete;
        bid_issues = curr_bid_freq.getIssues();
        bid_values = curr_bid_freq.getValues();

        if(agent_num == 0){
            for (Integer curr_key : bid_values.keySet()){
                lIssueDiscrete = (IssueDiscrete) (bid_issues.get(curr_key -1)); //取得issues
                lValueDiscrete = (ValueDiscrete) bid_values.get(curr_key); //取得value
                //每给一次offer，value++，update frequency
                freq_a [curr_key-1][lIssueDiscrete.getValueIndex(lValueDiscrete.getValue())] ++;
            }
        }
//        else if(agent_num == 1){ //和上边相同
//            for (Integer curr_key : bid_values.keySet()){
//                lIssueDiscrete = (IssueDiscrete) (bid_issues.get(curr_key -1));
//                lValueDiscrete = (ValueDiscrete) bid_values.get(curr_key);
//                freq_b [curr_key-1][lIssueDiscrete.getValueIndex(lValueDiscrete.getValue())] ++;
//            }
//        }

//        int value_index , issue_idx;
//        issue_idx = 0;
//        for(Issue lIssue : bid_issues){
//            lIssueDiscrete = (IssueDiscrete)  lIssue; //current issue
//            issue_values  = lIssueDiscrete.getValues();
//            for (ValueDiscrete value : issue_values) {
//                value_index = lIssueDiscrete.getValueIndex(value.getValue());
//            }
//
//            issue_idx ++;
//        }
    }

    //******************************************************************************************************************
    public Bid generateGoodBid(){
        //Traverse all issues on the Bid ...
        double max_value , curr_value ,randr;
        //double curr_time;
        int max_value_idx , num_values , issue_idx;
        Random randomnr = new Random(); //创建一个新的随机数生成器
        Bid generated_bid;
        HashMap<Integer, Value> curr_bid_value = new HashMap<Integer, Value>();
        int selected_value;
        //generated_bid = null;
        //int agent_max_val_idx , agent_max_val , curr_max_agent_value;
        //randr =0;

        //curr_time = getTimeLine().getTime(); //取得现在的时间

        for(Issue lIssue : domain_issues) {
            num_values = 0 ;
            issue_idx = 0;
            max_value = 0;
            max_value_idx = 0;
            curr_value = 0;
            IssueDiscrete lIssueDiscrete = (IssueDiscrete) lIssue;
            //对每一个value进行评估
            evaluator = (EvaluatorDiscrete) (((AdditiveUtilitySpace)utilitySpace).getEvaluator(lIssue.getNumber()));
            values = lIssueDiscrete.getValues(); //list of values for this particular issue


            //Get the number of the max value ...
            for (ValueDiscrete value : values ) {
                try{
                    curr_value = evaluator.getEvaluation(value);}
                catch (Exception e) {
                    e.printStackTrace();
                }
                if(curr_value > max_value){ //对max_value更新
                    max_value = curr_value;
                    max_value_idx =  lIssueDiscrete.getValueIndex(value.getValue()); //得到第几个value最大
                }
                num_values ++; //count the number of values
            }

            //Throw a coin on the re-normalized weight ...
            if(additiveUtilitySpace_i.getWeight(lIssue.getNumber()) != max_weight){ //现在的issue不是最大weight的issue，则里边value可以调节
                if(Math.random() >  0.1 + (additiveUtilitySpace_i.getWeight(lIssue.getNumber())  - min_weight )/ (max_weight - min_weight))
                { //取得0～1之间的随机数 double

                    selected_value = randomnr.nextInt(num_values);
                }

                else {
                    selected_value = max_value_idx;
                }
            }
            else {
                selected_value = max_value_idx;
            }

            curr_bid_value.put(lIssue.getNumber(), lIssueDiscrete.getValue(selected_value)); //更新current bid value
            issue_idx ++;
        }

        generated_bid = new Bid(utilitySpace.getDomain(), curr_bid_value); //根据current bid value 产生新的bid
        return generated_bid;
    }


    // Method used to decided whether to Accept a Bid or Reject it
    public boolean acceptOrOffer(Bid bid, double target) {
        if(this.utilitySpace.getUtility(bid) < target) {
            return false;
        }
        else {
            return true;
        }
    }

    // Method used to get the target Utility for our Agent based on given parameters and on time
    public double getTargetUtility(double k, double b) {
        return Umax + (Umin - Umax)*(k + (1-k)*Math.pow((getTimeLine().getTime()), b));
    }

    // Method used to get the Bid with most Utility for our Agent
    private Bid getMaxUtilityBid() {
        try {
            return this.utilitySpace.getMaxUtilityBid();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // Method used to get the Bid with least Utility for our Agent
    private Bid getMinUtilityBid() {
        try {
            return this.utilitySpace.getMinUtilityBid();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    //******************************************************************************************************************
    private Bid generateNashBid(){

        double To ,  kmax , curr_energy , next_energy , our_utility, predicted_b , predicted_a , a , b , alpha , T ;
        Bid altered_bid;
        int random_issue_1 , random_issue_2;
        HashMap<Integer, Value> curr_bid_value = new HashMap<Integer, Value>(); //auxiliary list for building the bid..
        java.util.HashMap<java.lang.Integer,Value> 	bid_values;
        //java.util.List<ValueDiscrete> issue_values;

        a = 0.3;
        b = 0.3;

        alpha = 0.1;

        To = 26;

        kmax = 4000; // Tune this parameter , # of iterations....

        curr_bid = getMaxUtilityBid(); // we will start with max utility bid ....

        Random randomnr = new Random();

        //****************************** 在自己最大utility下 ********************************
        for(int k = 0 ; k < kmax ; k++){
            our_utility = this.utilitySpace.getUtility(curr_bid); //得到自己的utility
            //Used to model uncertainty of the model ...
            if(Math.random() > 0.5){
                predicted_a = opponentA.predictUtility(curr_bid) + 0.1 * opponentA.predictUtility(curr_bid) ;}
            else{
                predicted_a = opponentA.predictUtility(curr_bid) - 0.1 * opponentA.predictUtility(curr_bid) ;
            }

//            if(Math.random() > 0.5){
//                predicted_b = opponentB.predictUtility(curr_bid) + 0.1 * opponentB.predictUtility(curr_bid) ;}
//            else{
//                predicted_b = opponentB.predictUtility(curr_bid) - 0.1 * opponentB.predictUtility(curr_bid) ;
//            }

            T =  To * Math.pow(alpha , k); //alpha的k次方

            bid_values = curr_bid.getValues();
            //Randomly select an issue and alter it ...
            random_issue_1 = randomnr.nextInt(number_of_issues+1); //随机产生offer
            //
            curr_energy = our_utility * predicted_a +
                    a * (1.0/Math.max(0.01, Math.abs(our_utility - predicted_a)));

            //****************************** 产生自己新的offer ********************************
            for(Issue lIssue : domain_issues){
                IssueDiscrete lIssueDiscrete = (IssueDiscrete) lIssue;
                if( random_issue_1 != lIssue.getNumber() ){
                    curr_bid_value.put(lIssue.getNumber() , bid_values.get(lIssue.getNumber()));}
                //alter one single issue putting a random value on it ...
                else {
                    curr_bid_value.put(lIssue.getNumber() , lIssueDiscrete.getValue(randomnr.nextInt( lIssueDiscrete.getNumberOfValues())));
                }
            }
            altered_bid = new Bid(utilitySpace.getDomain(), curr_bid_value); //产生新的offer
            our_utility = this.utilitySpace.getUtility(altered_bid); //计算新offer的utility

            //预测对手的utility
            if(Math.random() > 0.5){ //
                predicted_a = opponentA.predictUtility(altered_bid) + 0.1 * opponentA.predictUtility(altered_bid) ;}
            else{
                predicted_a = opponentA.predictUtility(altered_bid) - 0.1 * opponentA.predictUtility(altered_bid) ;
            }

//            if(Math.random() > 0.5){
//                predicted_b = opponentB.predictUtility(altered_bid) + 0.1 * opponentB.predictUtility(altered_bid) ;}
//            else{
//                predicted_b = opponentB.predictUtility(altered_bid) - 0.1 * opponentB.predictUtility(altered_bid) ;
//            }

            next_energy = our_utility * predicted_a +
                    a * (1.0/Math.max(0.01, Math.abs(our_utility - predicted_a)));

            //如果新产生的offer的utility大于目标utility
            if(this.utilitySpace.getUtility(altered_bid) > getTargetUtility( 0.1 , 1.5) ){
                if(next_energy > curr_energy ){
                    curr_bid = new Bid(altered_bid);
                }
                else if ( Math.exp((next_energy - curr_energy)/T) > Math.random()){
                    curr_bid = new Bid(altered_bid);
                }
            }
        }

        return curr_bid;
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
//                System.out.println(issue.getName() + ": " + b.getValue(issue.getNumber()));
//                System.out.println(issue.getNumber()+"*****************************");
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


    // Method to return a Bid whose utility is the average of the best Offers from the other two Agents

    public String getDescription() {
        return description;
    }
}