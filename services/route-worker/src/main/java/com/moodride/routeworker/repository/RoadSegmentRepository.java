package com.moodride.routeworker.repository;

import com.moodride.datamodels.RoadSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoadSegmentRepository extends JpaRepository<RoadSegment, Long> {
}
