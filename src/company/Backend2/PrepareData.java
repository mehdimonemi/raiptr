package company.Backend2;

import company.Data.Station;
import company.Data.newBlock;
import company.Data.newWagon;
import ilog.concert.IloException;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloNumVarType;
import ilog.cplex.IloCplex;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import static company.Backend2.Formation.minimumAllowedArc;
import static company.sql.*;

public class PrepareData {

    public void prepareData() {
        System.out.println("start trainArc");
        dizelsKey = new ArrayList<>();
        dizelsKey.addAll(dizelListMap.keySet());
        try {
            IloCplex model = new IloCplex();
            IloNumVar[] X = new IloNumVar[blockMap.size()];
            IloNumExpr goalFunction;
            IloNumExpr constraint;

            Iterator<Map.Entry<Long, newWagon>> wagonIterator = wagonListMap.entrySet().iterator();
            while (wagonIterator.hasNext()) {
                Map.Entry<Long, newWagon> wagon = wagonIterator.next();
                long wagonId = wagon.getKey();
                newWagon commodity = wagonListMap.get(wagonId);
                int stationA = commodity.getLastStation();
                int stationB = commodity.getDestination();
                //stop and reach to destination wagon are not consider in model
                if (commodity.getStatus() == 0 || commodity.getStatus() == 3) {
                    continue;
                }
                //to decrease proses time, duplicate od will  pass but their train arc will add
                if (od.containsKey(stationA + "-" + stationB)) {
                    commodity.getTrainArcs().addAll(od.get(stationA + "-" + stationB));
                    continue;
                }
                //start solving model for the commodity
                for (int i = 0; i < blockMap.size(); i++) {
                    X[i] = model.numVar(0, 1, IloNumVarType.Int);
                }
                Integer[] blocksKey = blockMap.keySet().toArray(new Integer[0]);
                goalFunction = model.constant(0);
                for (int i = 0; i < blockMap.size(); i++) {
                    goalFunction = model.sum(goalFunction, model.prod(X[i], blockMap.get(blocksKey[i]).getLengthGIS()));
                }
                model.addMinimize(goalFunction);

                // constraints
                for (int key : stationMap.keySet()) {
                    constraint = model.constant(0);
                    if (key == stationA) {
                        for (int j = 0; j < blockMap.size(); j++) {
                            if (stationA == blockMap.get(blocksKey[j]).getStartStationID()) {
                                constraint = model.sum(constraint, X[j]);
                            }
                            if (stationA == blockMap.get(blocksKey[j]).getEndStationID()) {
                                constraint = model.sum(constraint, model.negative(X[j]));
                            }
                        }
                        model.addEq(constraint, 1);
                    } else if (key == (stationB)) {
                        for (int j = 0; j < blockMap.size(); j++) {
                            if (stationB == blockMap.get(blocksKey[j]).getStartStationID()) {
                                constraint = model.sum(constraint, X[j]);
                            }
                            if (stationB == blockMap.get(blocksKey[j]).getEndStationID()) {
                                constraint = model.sum(constraint, model.negative(X[j]));
                            }
                        }
                        model.addEq(constraint, -1);
                    } else {
                        for (int j = 0; j < blockMap.size(); j++) {
                            if (key == (blockMap.get(blocksKey[j]).getStartStationID())) {
                                constraint = model.sum(constraint, X[j]);
                            }
                            if (key == (blockMap.get(blocksKey[j]).getEndStationID())) {
                                constraint = model.sum(constraint, model.negative(X[j]));
                            }
                        }
                        model.addEq(constraint, 0);
                    }
                } // end of constraints

                model.setOut(null);
                ArrayList<newBlock> tempBlocks1 = new ArrayList<>();
                if (model.solve()) {
                    if (model.getObjValue() < minimumAllowedArc) {
                        wagonIterator.remove();
                        model.clearModel();
                        for (int i = 0; i < blockMap.size(); i++) {
                            if (X[i] != null) {
                                X[i] = null;
                            }
                        }
                        continue;
                    }

                    //first add info for priorities
                    commodity.setDistance((long) model.getObjValue());
                    if (stationMap.get(commodity.getDestination()).getStationCapacity().containsKey(commodity.getFreight())) {

                        if (commodity.getFreight() == 1883)
                            stationMap.get(commodity.getDestination()).getStationCapacity().
                                    get(commodity.getFreight()).comingEmptyWagons.add(wagonId);
                        else
                            stationMap.get(commodity.getDestination()).getStationCapacity().
                                    get(commodity.getFreight()).comingLoadWagons.add(wagonId);
                    }

                    wagonListMap.get(wagonId).setDistance((int) model.getObjValue());
                    for (int i = 0; i < blockMap.size(); i++) {
                        if (model.getValue(X[i]) > 0.5) {
                            tempBlocks1.add(blockMap.get(blocksKey[i]));
                        }
                    }

                    //sort blocks
                    int tempOrigin = stationA;
                    ArrayList<newBlock> tempBlocks2 = new ArrayList<>();

                    while (!tempBlocks1.isEmpty()) {
                        for (newBlock block : tempBlocks1) {
                            if (block.getStartStationID() == (tempOrigin)) {
                                tempBlocks2.add(block);
                                tempOrigin = block.getEndStationID();
                                tempBlocks1.remove(block);
                                break;
                            }
                        }
                    }

                    Iterator<newBlock> iterator = tempBlocks2.iterator();

                    newBlock block = iterator.next();

                    int arcStart = block.getStartStationID();
                    int arcEnd = block.getEndStationID();
                    int arcWight = block.getTrainWeight();
                    int arcLength = block.getTrainLength();
                    int arcDistance = block.getLength();
                    int arcEfficiency = block.getTrainWeight();

                    ArrayList<Integer> arcBlocks = new ArrayList<>();
                    arcBlocks.add(getBlockId(blocksKey, block));
                    //create basic trainArcs
                    while (iterator.hasNext()) {
                        block = (newBlock) iterator.next();
                        if (block.getTrainWeight() != arcWight) {
                            //agar block vazne na mosavi dasht traicarc ra ta sare in block ezafe mikonim
                            //bad train arc ra az abtedai in block edame midahom
                            TrainArc trainArc = new TrainArc(arcStart, arcEnd, arcWight, arcLength, arcDistance,
                                    arcEfficiency / (float) arcWight);
                            if (!trainArcs.contains(trainArc)) {
                                trainArc.getBlocks().addAll(arcBlocks);
                                trainArcs.add(trainArc);
                                addPossibleDizel2Arcs(trainArc, arcBlocks);
                                arcBlocks = new ArrayList<>();
                            }
                            commodity.getTrainArcs().add(trainArcs.indexOf(trainArc));
                            arcBlocks.add(getBlockId(blocksKey, block));
                            arcStart = block.getStartStationID();
                            arcEnd = block.getEndStationID();
                            arcWight = block.getTrainWeight();
                            arcLength = block.getTrainLength();
                            arcDistance = block.getLength();
                            arcEfficiency = block.getTrainWeight();
                        } else if (!iterator.hasNext()) {
                            //agar mosavi bood va digar blocki nabood, train arc ra ta entehai block ezafe mikonim
                            arcBlocks.add(getBlockId(blocksKey, block));
                            arcEnd = block.getEndStationID();
                            arcDistance += block.getLength();
                            arcEfficiency = block.getTrainWeight();
                            TrainArc trainArc = new TrainArc(arcStart, arcEnd, arcWight, arcLength, arcDistance,
                                    arcEfficiency / (float) arcWight);
                            if (!trainArcs.contains(trainArc)) {
                                trainArc.getBlocks().addAll(arcBlocks);
                                trainArcs.add(trainArc);
                                addPossibleDizel2Arcs(trainArc, arcBlocks);
                                arcBlocks = new ArrayList<>();
                            }
                            commodity.getTrainArcs().add(trainArcs.indexOf(trainArc));
                        } else {
                            //agar mosavi bood va baz block vojood dasht, block ra be train arc ezafe mikonim
                            arcBlocks.add(getBlockId(blocksKey, block));
                            arcEnd = block.getEndStationID();
                            arcDistance += block.getLength();
                            arcEfficiency = block.getTrainWeight();
                        }
                    }

                    TrainArc trainArc;
                    //create possible arcs by combining basic arcs
                    int size = commodity.getTrainArcs().size();
                    for (int i = 0; i < size; i++) {
                        arcBlocks = new ArrayList<>();
                        arcBlocks.addAll(trainArcs.get(commodity.getTrainArcs().get(i)).getBlocks());
                        int tempDistance = trainArcs.get(commodity.getTrainArcs().get(i)).getDistance();
                        int minWeight = trainArcs.get(commodity.getTrainArcs().get(i)).getMaxWeight();
                        int minLength = trainArcs.get(commodity.getTrainArcs().get(i)).getMaxLength();
                        arcEfficiency = trainArcs.get(commodity.getTrainArcs().get(i)).getMaxWeight();
                        for (int j = i + 1; j < size; j++) {
                            arcBlocks.addAll(trainArcs.get(commodity.getTrainArcs().get(j)).getBlocks());
                            tempDistance += trainArcs.get(commodity.getTrainArcs().get(j)).getDistance();
                            minWeight = Math.min(minWeight,
                                    trainArcs.get(commodity.getTrainArcs().get(j)).getMaxWeight());
                            arcEfficiency = Math.max(arcEfficiency, trainArcs.get(commodity.getTrainArcs().get(j)).getMaxWeight());
                            minLength = Math.min(minLength, trainArcs.get(commodity.getTrainArcs().get(j)).getMaxLength());
                            trainArc = new TrainArc(
                                    trainArcs.get(commodity.getTrainArcs().get(i)).getOrigin(),
                                    trainArcs.get(commodity.getTrainArcs().get(j)).getDestination(),
                                    minWeight,
                                    minLength,
                                    tempDistance,
                                    arcEfficiency / (float) minWeight
                            );
                            if (!trainArcs.contains(trainArc)) {
                                trainArc.getBlocks().addAll(arcBlocks);
                                trainArcs.add(trainArc);
                                addPossibleDizel2Arcs(trainArc, arcBlocks);
                            }
                            commodity.getTrainArcs().add(trainArcs.indexOf(trainArc));
                        }
                    }
                    od.put(stationA + "-" + stationB, commodity.getTrainArcs());
                } else {
                    System.out.println("No trainArc for commodity: " + stationMap.get(stationA).getName() +
                            "--" + stationB);
                }
                model.clearModel();
                for (int i = 0; i < blockMap.size(); i++) {
                    if (X[i] != null) {
                        X[i] = null;
                    }
                }

                goalFunction = null;
                constraint = null;
            }
            System.out.println("end train Arcs");
        } catch (IloException e) {
            e.printStackTrace();
        }
    }

    public void setPriority() {
        for (Map.Entry<Integer, Station> station : stationMap.entrySet()) {
            for (Map.Entry<Integer, Station.Capacity> freight : station.getValue().getStationCapacity().entrySet()) {
                if (2 * freight.getValue().unloadingCap <= freight.getValue().comingLoadWagons.size()) {
                    for (Long wagonId : freight.getValue().comingLoadWagons) {
                        wagonListMap.get(wagonId).setPriority(0);
                    }
                } else {
                    for (Long wagonId : freight.getValue().comingLoadWagons) {
                        wagonListMap.get(wagonId).setPriority(
                                ((float) freight.getValue().unloadingCap - freight.getValue().comingLoadWagons.size())
                                        / (wagonListMap.get(wagonId).getDistance())
                        );
                    }
                }

                if (2 * freight.getValue().loadingCap <= freight.getValue().comingEmptyWagons.size()) {
                    for (Long wagonId : freight.getValue().comingEmptyWagons) {
                        wagonListMap.get(wagonId).setPriority(0);
                    }
                } else {
                    for (Long wagonId : freight.getValue().comingEmptyWagons) {
                        wagonListMap.get(wagonId).setPriority(
                                ((float) freight.getValue().loadingCap - freight.getValue().comingEmptyWagons.size())
                                        / (wagonListMap.get(wagonId).getDistance())
                        );
                    }
                }
            }
        }
    }

    private void addPossibleDizel2Arcs(TrainArc trainArc, ArrayList<Integer> arcBlocks) {
        for (Integer key : dizelsKey) {
            int powerOnArc = 100000;
            if (dizelListMap.get(key).getAllowedBlock().keySet().containsAll(arcBlocks)) {
                for (Integer block : arcBlocks) {
                    int a = dizelListMap.get(key).getAllowedBlock().get(block);
                    powerOnArc = Math.min(powerOnArc, a);
                }
                dizelListMap.get(key).getTrainArcs().put(trainArcs.indexOf(trainArc), powerOnArc);
            }
        }
    }

    private boolean dizelHaveStation(Integer dizelkey, int station) {
        for (Integer trainArc : dizelListMap.get(dizelkey).getTrainArcs().keySet()) {
            if (trainArcs.get(trainArc).getOrigin() == station)
                return true;
            if (trainArcs.get(trainArc).getDestination() == station)
                return true;
        }
        for (Integer block : dizelListMap.get(dizelkey).getAllowedBlock().keySet()) {
            if (blockMap.get(block).getStartStationID() == station)
                return true;
            if (blockMap.get(block).getEndStationID() == station)
                return true;
        }
        return false;
    }

    private Integer getBlockId(Integer[] blocksKey, newBlock block) {
        for (Integer key : blocksKey) {
            if (blockMap.get(key).equals(block))
                return key;

        }
        return null;
    }

}
