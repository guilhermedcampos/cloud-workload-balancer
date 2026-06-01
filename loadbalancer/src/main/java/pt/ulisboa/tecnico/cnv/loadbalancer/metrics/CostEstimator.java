package pt.ulisboa.tecnico.cnv.loadbalancer.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CostEstimator {

    Map<String,Integer> costParams;

    public CostEstimator(List<String> params, List<Integer> cost){
        costParams = new HashMap<>();
        for (int i = 0; i < params.size(); i++) {
            costParams.put(params.get(i), cost.get(i));
        }
    }
    
    public int estimate(Map<String, Integer> quantities) {
        int totalCost = 0;
        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            String param = entry.getKey();
            int quantity = entry.getValue();
            if (costParams.containsKey(param)) {
                totalCost += costParams.get(param) * quantity;
            }
        }
        return totalCost;
    }
}
