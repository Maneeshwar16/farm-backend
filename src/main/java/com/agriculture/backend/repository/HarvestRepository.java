package com.agriculture.backend.repository;
import com.agriculture.backend.entity.Harvest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface HarvestRepository extends JpaRepository<Harvest, Long> {

    List<Harvest> findByFarmerId(Long farmerId);

    List<Harvest> findByCropId(Long cropId);
}
