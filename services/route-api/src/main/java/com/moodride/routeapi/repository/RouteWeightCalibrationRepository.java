package com.moodride.routeapi.repository;

import com.moodride.datamodels.RouteWeightCalibration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface RouteWeightCalibrationRepository extends JpaRepository<RouteWeightCalibration, String> {
    List<RouteWeightCalibration> findByVibeIn(Collection<String> vibes);
}

