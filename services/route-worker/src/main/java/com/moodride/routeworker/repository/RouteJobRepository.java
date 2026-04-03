package com.moodride.routeworker.repository;

import com.moodride.datamodels.RouteJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface RouteJobRepository extends JpaRepository<RouteJob, UUID> {
}
