package com.moodride.routeworker.repository;

import com.moodride.datamodels.ScenicScoreTile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ScenicScoreTileRepository extends JpaRepository<ScenicScoreTile, String> {
    List<ScenicScoreTile> findByH3IndexIn(Collection<String> h3Indexes);
}

