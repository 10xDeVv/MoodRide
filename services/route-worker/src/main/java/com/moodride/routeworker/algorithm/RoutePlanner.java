package com.moodride.routeworker.algorithm;

import com.moodride.datamodels.RouteJob;
import com.moodride.routeworker.service.GraphService;
import org.springframework.stereotype.Service;

@Service
public class RoutePlanner {
    
    private final GraphService graphService;
    private final BeamSearchSolver beamSearchSolver;
    
    public RoutePlanner(GraphService graphService, BeamSearchSolver beamSearchSolver) {
        this.graphService = graphService;
        this.beamSearchSolver = beamSearchSolver;
    }
    
    public RouteCandidate generateRoute(RouteJob job) {
        var graph = graphService.getGraph();
        
        return beamSearchSolver.solveOrienteering(
            graph,
            job.getStartLatitude(),
            job.getStartLongitude(),
            job.getTimeBudgetMinutes()
        );
    }
}
