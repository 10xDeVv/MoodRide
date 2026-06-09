package com.moodride.routeworker.repository;

import com.moodride.datamodels.RouteDurationCalibration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RouteDurationCalibrationRepository extends JpaRepository<RouteDurationCalibration, String> {
}
