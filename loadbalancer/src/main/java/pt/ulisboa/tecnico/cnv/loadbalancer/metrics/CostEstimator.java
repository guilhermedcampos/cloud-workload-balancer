package pt.ulisboa.tecnico.cnv.loadbalancer.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CostEstimator {

    Map<String,Double> costParams;
    private final int FRACTALS_COST_LIMIT = 300_000;


    public CostEstimator(List<String> params, List<Double> cost){
        costParams = new HashMap<>();
        for (int i = 0; i < params.size(); i++) {
            costParams.put(params.get(i), cost.get(i));
        }
    }
    
    public double estimate(String workload, Map<String, Integer> quantities) {
        double totalCost = 0;
        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            String param = entry.getKey();
            double quantity = entry.getValue().doubleValue();
            if (costParams.containsKey(param)) {
                totalCost += costParams.get(param) * quantity;
            }
        }


        if ("fractals".equals(workload)) {
            totalCost = Math.min(totalCost, FRACTALS_COST_LIMIT); 
        }

        return totalCost;
    }
}
